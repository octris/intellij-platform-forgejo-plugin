package octris.forgejo.actions

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.util.treeView.TreeState
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import kotlinx.coroutines.runBlocking
import octris.forgejo.ForgejoBundle
import octris.forgejo.api.ForgejoActionStatus
import octris.forgejo.api.ForgejoApiClient
import octris.forgejo.api.ForgejoRun
import octris.forgejo.api.ForgejoTask
import octris.forgejo.settings.ForgejoAccountManager
import octris.forgejo.vcs.ForgejoRepoContext
import octris.forgejo.vcs.ForgejoRepoResolver
import java.awt.FlowLayout
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

/**
 * "Forgejo CI" tool window content: a runs → jobs tree for the project's Forgejo repo, with a repo
 * selector (when there are multiple Forgejo remotes), refresh, and open-in-browser. Runs are derived
 * from the lightweight `/actions/tasks` endpoint. While anything is in progress it auto-refreshes,
 * animates the spinner, and ticks the elapsed time live; expansion/selection survive refreshes.
 */
class ForgejoActionsPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private val root = DefaultMutableTreeNode()
    private val treeModel = DefaultTreeModel(root)
    private val tree = Tree(treeModel).apply {
        isRootVisible = false
        showsRootHandles = true
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        cellRenderer = CellRenderer()
        emptyText.text = ForgejoBundle.message("actions.loading")
    }

    private val repoCombo = ComboBox<ForgejoRepoContext>().apply {
        renderer = SimpleListCellRenderer.create("") { ctx -> "${ctx.owner}/${ctx.repo}  —  ${ctx.account.name}@${ctx.account.server}" }
        isVisible = false
        addActionListener { if (!updatingCombo) reloadRuns() }
    }

    private val refreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val spinnerAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    @Volatile
    private var disposed = false
    private var updatingCombo = false
    private var lastContext: ForgejoRepoContext? = null

    init {
        val group = DefaultActionGroup().apply {
            add(RefreshAction())
            add(OpenInBrowserAction())
        }
        val actionToolbar = ActionManager.getInstance().createActionToolbar("ForgejoActions", group, true)
        actionToolbar.targetComponent = tree
        val top = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            add(actionToolbar.component)
            add(repoCombo)
        }
        setToolbar(top)
        setContent(JBScrollPane(tree))
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                openSelected()
                return true
            }
        }.installOn(tree)
        loadContexts()
    }

    /** Re-scans the project's Forgejo remotes and (re)populates the selector, preserving the choice. */
    private fun loadContexts() {
        if (root.childCount == 0) tree.emptyText.text = ForgejoBundle.message("actions.loading")
        ApplicationManager.getApplication().executeOnPooledThread {
            if (disposed) return@executeOnPooledThread
            val contexts = ForgejoRepoResolver.allContexts(project)
            ApplicationManager.getApplication().invokeLater({
                if (disposed) return@invokeLater
                val previous = repoCombo.selectedItem as? ForgejoRepoContext
                updatingCombo = true
                repoCombo.removeAllItems()
                contexts.forEach { repoCombo.addItem(it) }
                repoCombo.isVisible = contexts.size > 1
                repoCombo.selectedItem = contexts.firstOrNull { it == previous } ?: contexts.firstOrNull()
                updatingCombo = false
                if (contexts.isEmpty()) setRootEmpty(ForgejoBundle.message("actions.notConfigured")) else reloadRuns()
            }, ModalityState.any())
        }
    }

    private fun reloadRuns() {
        val ctx = repoCombo.selectedItem as? ForgejoRepoContext
            ?: return setRootEmpty(ForgejoBundle.message("actions.notConfigured"))
        val freshView = ctx != lastContext
        lastContext = ctx
        val state = if (freshView) null else TreeState.createOn(tree)
        if (root.childCount == 0) tree.emptyText.text = ForgejoBundle.message("actions.loading")
        ApplicationManager.getApplication().executeOnPooledThread {
            if (disposed) return@executeOnPooledThread
            val token = runBlocking { service<ForgejoAccountManager>().findCredentials(ctx.account) }
            if (token.isNullOrEmpty()) return@executeOnPooledThread setRootEmptyAsync(ForgejoBundle.message("actions.notConfigured"))
            val runs = ForgejoApiClient().listRuns(ctx.account.server.toString(), token, ctx.owner, ctx.repo).getOrElse { emptyList() }
            if (disposed) return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater({ populate(runs, state, freshView) }, ModalityState.any())
        }
    }

    private fun populate(runs: List<ForgejoRun>, state: TreeState?, expandAll: Boolean) {
        if (disposed) return
        root.removeAllChildren()
        var anyRunning = false
        for (run in runs) {
            if (run.status.isInProgress) anyRunning = true
            val runNode = RunNode(run)
            for (job in run.jobs) {
                if (job.status.isInProgress) anyRunning = true
                runNode.add(TaskNode(job))
            }
            root.add(runNode)
        }
        tree.emptyText.text = ForgejoBundle.message("actions.empty")
        treeModel.reload()
        if (expandAll) TreeUtil.expandAll(tree) else state?.applyTo(tree)
        scheduleAutoRefresh(anyRunning)
        scheduleSpinner(anyRunning)
    }

    private fun scheduleAutoRefresh(running: Boolean) {
        refreshAlarm.cancelAllRequests()
        if (!running) return
        refreshAlarm.addRequest({
            if (disposed) return@addRequest
            if (isShowing) reloadRuns() else scheduleAutoRefresh(true)
        }, AUTO_REFRESH_MS)
    }

    private fun scheduleSpinner(running: Boolean) {
        spinnerAlarm.cancelAllRequests()
        if (running) spinnerTick()
    }

    private fun spinnerTick() {
        if (disposed) return
        if (isShowing) tree.repaint()
        spinnerAlarm.addRequest({ spinnerTick() }, SPINNER_FRAME_MS)
    }

    /** EDT-only. */
    private fun setRootEmpty(message: String) {
        root.removeAllChildren()
        treeModel.reload()
        tree.emptyText.text = message
        refreshAlarm.cancelAllRequests()
        spinnerAlarm.cancelAllRequests()
    }

    private fun setRootEmptyAsync(message: String) {
        ApplicationManager.getApplication().invokeLater({ if (!disposed) setRootEmpty(message) }, ModalityState.any())
    }

    private fun openSelected() {
        val url = when (val node = tree.lastSelectedPathComponent) {
            is RunNode -> node.run.htmlUrl
            is TaskNode -> node.task.runUrl
            else -> null
        }
        if (!url.isNullOrEmpty()) BrowserUtil.browse(url)
    }

    override fun dispose() {
        disposed = true
    }

    private inner class RefreshAction :
        DumbAwareAction(ForgejoBundle.message("actions.refresh"), null, AllIcons.Actions.Refresh) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) = reloadRuns()
    }

    private inner class OpenInBrowserAction :
        DumbAwareAction(ForgejoBundle.message("actions.openInBrowser"), null, AllIcons.General.Web) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = tree.lastSelectedPathComponent is RunNode || tree.lastSelectedPathComponent is TaskNode
        }

        override fun actionPerformed(e: AnActionEvent) = openSelected()
    }

    private class RunNode(val run: ForgejoRun) : DefaultMutableTreeNode(run) {
        override fun toString(): String = "run:${run.index}"
    }

    private class TaskNode(val task: ForgejoTask) : DefaultMutableTreeNode(task) {
        override fun toString(): String = "job:${task.id}"
    }

    private class CellRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ) {
            when (value) {
                is RunNode -> {
                    val run = value.run
                    icon = run.status.icon()
                    append("#${run.index} ")
                    append(run.workflow)
                    append("  ${run.branch}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    appendTime(run.status, run.startedAtMillis, run.durationMillis)
                }

                is TaskNode -> {
                    val task = value.task
                    icon = task.status.icon()
                    append(task.name)
                    appendTime(task.status, task.startedAtMillis, task.durationMillis)
                }
            }
        }

        private fun appendTime(status: ForgejoActionStatus, startedAtMillis: Long, durationMillis: Long) {
            val text = when {
                status.isInProgress && startedAtMillis > 0 -> formatElapsed(System.currentTimeMillis() - startedAtMillis)
                durationMillis > 0 -> formatElapsed(durationMillis)
                else -> null
            }
            if (text != null) append("  $text", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }

    private companion object {
        const val AUTO_REFRESH_MS = 6000
        const val SPINNER_FRAME_MS = 130
    }
}
