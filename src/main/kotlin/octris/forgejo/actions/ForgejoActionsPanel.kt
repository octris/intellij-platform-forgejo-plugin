package octris.forgejo.actions

import com.intellij.dvcs.repo.VcsRepositoryManager
import com.intellij.dvcs.repo.VcsRepositoryMappingListener
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
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
import kotlinx.coroutines.runBlocking
import octris.forgejo.ForgejoBundle
import octris.forgejo.api.ForgejoActionStatus
import octris.forgejo.api.ForgejoApiClient
import octris.forgejo.api.ForgejoRun
import octris.forgejo.api.ForgejoRunsPage
import octris.forgejo.api.ForgejoTask
import octris.forgejo.api.ForgejoTaskRaw
import octris.forgejo.api.toJob
import octris.forgejo.settings.ForgejoAccountManager
import octris.forgejo.vcs.ForgejoRepoContext
import octris.forgejo.vcs.ForgejoRepoResolver
import java.awt.FlowLayout
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * "Forgejo CI" tool window content: a runs → jobs tree for the project's Forgejo repo, with a repo
 * selector (when there are multiple Forgejo remotes), refresh, and open-in-browser.
 *
 * The run list is paginated by run via `/actions/runs` and grows as you scroll (auto-filling the
 * viewport). Forgejo has no per-run jobs endpoint, so a run's jobs are loaded lazily the first time
 * it's expanded, by paging the bulk `/actions/tasks` feed down to that run number and caching it —
 * completed runs are instant on re-expand; in-progress runs (always newest) sit on the first task
 * page and refresh live. The VCS-log column drives it via [ForgejoActionsCoordinator].
 */
class ForgejoActionsPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private val client = ForgejoApiClient()
    private val coordinator = project.service<ForgejoActionsCoordinator>()

    private val root = DefaultMutableTreeNode()
    private val treeModel = DefaultTreeModel(root)
    private val tree = Tree(treeModel).apply {
        isRootVisible = false
        showsRootHandles = true
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        cellRenderer = CellRenderer()
        emptyText.text = ForgejoBundle.message("actions.loading")
    }
    private val scrollPane = JBScrollPane(tree)

    private val repoCombo = ComboBox<ForgejoRepoContext>().apply {
        renderer = SimpleListCellRenderer.create("") { ctx -> "${ctx.owner}/${ctx.repo}  —  ${ctx.account.name}@${ctx.account.server}" }
        isVisible = false
        addActionListener { if (!updatingCombo) reloadRuns() }
    }

    private val refreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val spinnerAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val retryAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    @Volatile
    private var disposed = false
    private var updatingCombo = false
    private var lastContext: ForgejoRepoContext? = null

    // Run-list pagination state for the selected repo.
    private val runNodesByIndex = HashMap<Long, RunNode>()
    private var runsTotalCount = 0
    private var runsPagesLoaded = 0
    private var runsHasMore = false
    private var retryCount = 0

    @Volatile
    private var loadingRuns = false

    // Lazy job loading: the bulk task feed isn't filterable, so we page it (newest-first) and cache.
    // Guarded by [taskLock] since several expansions may page it concurrently off the EDT.
    private val taskLock = Any()
    private val tasksById = LinkedHashMap<Long, ForgejoTaskRaw>()
    private var taskPagesFetched = 0
    private var allTasksLoaded = false

    private enum class LoadMode { RESET, REFRESH, APPEND }

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
        setContent(scrollPane)
        scrollPane.verticalScrollBar.addAdjustmentListener { maybeLoadMore() }
        tree.addTreeExpansionListener(object : TreeExpansionListener {
            override fun treeExpanded(event: TreeExpansionEvent) {
                (event.path.lastPathComponent as? RunNode)?.let { loadJobs(it, forceRefresh = false) }
            }

            override fun treeCollapsed(event: TreeExpansionEvent) {}
        })
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean {
                openSelected()
                return true
            }
        }.installOn(tree)
        // The tab may be created during startup restore, before Git4Idea has registered the
        // project's repositories — re-resolve contexts when the repository mapping changes (also
        // covers remotes added/removed later), otherwise we'd be stuck on "no account matches".
        project.messageBus.connect(this).subscribe(
            VcsRepositoryManager.VCS_REPOSITORY_MAPPING_UPDATED,
            VcsRepositoryMappingListener {
                ApplicationManager.getApplication().invokeLater({ if (!disposed) loadContexts() }, ModalityState.any())
            },
        )
        coordinator.register(this)
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

    /** (Re)loads the first page of runs — resets the tree on a repo switch, else refreshes in place. */
    private fun reloadRuns() {
        val ctx = repoCombo.selectedItem as? ForgejoRepoContext
            ?: return setRootEmpty(ForgejoBundle.message("actions.notConfigured"))
        val contextChanged = ctx != lastContext
        lastContext = ctx
        retryCount = 0
        retryAlarm.cancelAllRequests()
        loadRuns(ctx, 1, if (contextChanged) LoadMode.RESET else LoadMode.REFRESH)
    }

    /** Loads the next run page once the user scrolls near the bottom (infinite scroll). */
    private fun maybeLoadMore() {
        if (loadingRuns || !runsHasMore || runsPagesLoaded < 1) return
        val ctx = repoCombo.selectedItem as? ForgejoRepoContext ?: return
        val bar = scrollPane.verticalScrollBar
        if (bar.value + bar.visibleAmount >= bar.maximum - LOAD_MORE_THRESHOLD) {
            loadRuns(ctx, runsPagesLoaded + 1, LoadMode.APPEND)
        }
    }

    private fun loadRuns(ctx: ForgejoRepoContext, pageNum: Int, mode: LoadMode) {
        loadingRuns = true
        if (root.childCount == 0) tree.emptyText.text = ForgejoBundle.message("actions.loading")
        ApplicationManager.getApplication().executeOnPooledThread {
            if (disposed) return@executeOnPooledThread
            val token = runBlocking { service<ForgejoAccountManager>().findCredentials(ctx.account) }
            if (token.isNullOrEmpty()) {
                loadingRuns = false
                return@executeOnPooledThread setRootEmptyAsync(ForgejoBundle.message("actions.notConfigured"))
            }
            val result = client.listRunsPage(ctx.account.server.toString(), token, ctx.owner, ctx.repo, pageNum, RUNS_PAGE_SIZE).getOrNull()
            if (disposed) return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater({ applyRunsPage(ctx, pageNum, mode, result) }, ModalityState.any())
        }
    }

    private fun applyRunsPage(ctx: ForgejoRepoContext, pageNum: Int, mode: LoadMode, result: ForgejoRunsPage?) {
        loadingRuns = false
        if (disposed) return
        if (!isCurrentRepo(ctx)) return // a load for a repo the user has since switched away from

        if (mode == LoadMode.RESET) resetState()

        if (result == null) { // fetch failed (e.g. a transient error at startup) — retry, don't show "empty"
            if (mode != LoadMode.APPEND && runNodesByIndex.isEmpty() && retryCount < MAX_RETRIES) {
                retryCount++
                thisLogger().warn("Forgejo CI: run-list fetch failed for ${ctx.owner}/${ctx.repo}, retry $retryCount/$MAX_RETRIES")
                retryAlarm.cancelAllRequests()
                retryAlarm.addRequest({ if (!disposed && isCurrentRepo(ctx)) loadRuns(ctx, 1, LoadMode.RESET) }, RETRY_DELAY_MS)
            } else if (runNodesByIndex.isEmpty()) {
                tree.emptyText.text = ForgejoBundle.message("actions.loadFailed")
            }
            return
        }
        retryCount = 0

        runsTotalCount = result.totalCount
        runsPagesLoaded = maxOf(runsPagesLoaded, pageNum)
        result.runs.forEach { upsertRun(it) }
        runsHasMore = runNodesByIndex.size < runsTotalCount
        // With an invisible root, its children only render while the root itself is expanded — and a
        // model reset collapses it. Re-expand after populating, or the tree looks empty even though
        // the runs are in the model (the symptom that a VCS-log click's scrollPathToVisible masked).
        if (root.childCount > 0) tree.expandPath(TreePath(root))

        tree.emptyText.text = ForgejoBundle.message("actions.empty")
        val anyRunning = runNodesByIndex.values.any { it.run.status.isInProgress }
        scheduleAutoRefresh(anyRunning)
        scheduleSpinner(anyRunning)
        if (mode == LoadMode.REFRESH) refreshExpandedInProgressJobs()
        revealPending()
        maybeFillViewport()
    }

    /** Adds a new run node (in run-number order) or updates an existing one's row in place. */
    private fun upsertRun(run: ForgejoRun) {
        val existing = runNodesByIndex[run.index]
        if (existing != null) {
            existing.run = run
            treeModel.nodeChanged(existing)
            return
        }
        val node = RunNode(run).apply { add(MessageNode(ForgejoBundle.message("actions.jobsLoading"))) }
        runNodesByIndex[run.index] = node
        val position = (0 until root.childCount).count { (root.getChildAt(it) as RunNode).run.index > run.index }
        treeModel.insertNodeInto(node, root, position)
    }

    /** Auto-loads further pages until the list overflows the viewport (so collapsed runs are scrollable). */
    private fun maybeFillViewport() {
        SwingUtilities.invokeLater {
            if (disposed || loadingRuns || !runsHasMore) return@invokeLater
            val ctx = repoCombo.selectedItem as? ForgejoRepoContext ?: return@invokeLater
            val bar = scrollPane.verticalScrollBar
            if (bar.visibleAmount > 0 && bar.maximum <= bar.visibleAmount) {
                loadRuns(ctx, runsPagesLoaded + 1, LoadMode.APPEND)
            }
        }
    }

    /** Lazily resolves a run's jobs from the bulk task feed the first time it's expanded (cached). */
    private fun loadJobs(node: RunNode, forceRefresh: Boolean) {
        if (node.jobsLoading) return
        if (node.jobsLoaded && !forceRefresh) return
        val ctx = repoCombo.selectedItem as? ForgejoRepoContext ?: return
        node.jobsLoading = true
        val index = node.run.index
        ApplicationManager.getApplication().executeOnPooledThread {
            if (disposed) return@executeOnPooledThread
            val token = runBlocking { service<ForgejoAccountManager>().findCredentials(ctx.account) }
            val jobs = if (token.isNullOrEmpty()) {
                emptyList()
            } else {
                synchronized(taskLock) {
                    ensureTasksDownTo(ctx, token, index, forceRefresh)
                    // A re-run shows up as another task with the same name (Forgejo exposes no attempt
                    // field), so collapse to the latest attempt per job and surface the attempt count.
                    tasksById.values.filter { it.runNumber == index }
                        .groupBy { it.name }
                        .map { (_, attempts) -> attempts.maxByOrNull { it.id }!!.toJob().copy(attempts = attempts.size) }
                        .sortedBy { it.id }
                }
            }
            if (disposed) return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater({
                if (!disposed) setJobs(node, jobs)
                node.jobsLoaded = true
                node.jobsLoading = false
            }, ModalityState.any())
        }
    }

    /** Pages the task feed (under [taskLock], off the EDT) until [index]'s jobs are fully cached. */
    private fun ensureTasksDownTo(ctx: ForgejoRepoContext, token: String, index: Long, forceRefresh: Boolean) {
        val server = ctx.account.server.toString()
        if (forceRefresh) {
            // Re-fetch the newest pages covering this (in-progress, hence newest) run with fresh data.
            var page = 0
            while (true) {
                page++
                val result = client.listTasksPage(server, token, ctx.owner, ctx.repo, page, TASKS_PAGE_SIZE).getOrNull() ?: break
                result.tasks.forEach { tasksById[it.id] = it }
                taskPagesFetched = maxOf(taskPagesFetched, page)
                val coveredMin = result.tasks.minOfOrNull { it.runNumber } ?: Long.MAX_VALUE
                if (coveredMin < index || result.tasks.size < TASKS_PAGE_SIZE) break
            }
            return
        }
        while (!allTasksLoaded) {
            val min = tasksById.values.minOfOrNull { it.runNumber } ?: Long.MAX_VALUE
            if (min < index) break // we've paged past this run, so all its tasks are cached
            val result = client.listTasksPage(server, token, ctx.owner, ctx.repo, taskPagesFetched + 1, TASKS_PAGE_SIZE).getOrNull() ?: break
            taskPagesFetched++
            result.tasks.forEach { tasksById[it.id] = it }
            if (result.tasks.size < TASKS_PAGE_SIZE) {
                allTasksLoaded = true
                break
            }
        }
    }

    /** EDT-only. Replaces a run node's children with its jobs (or a placeholder), keeping it expanded. */
    private fun setJobs(node: RunNode, jobs: List<ForgejoTask>) {
        node.removeAllChildren()
        if (jobs.isEmpty()) {
            node.add(MessageNode(ForgejoBundle.message("actions.noJobs")))
        } else {
            jobs.forEach { node.add(TaskNode(it)) }
        }
        treeModel.nodeStructureChanged(node)
        tree.expandPath(TreePath(node.path))
    }

    private fun refreshExpandedInProgressJobs() {
        for (i in 0 until root.childCount) {
            val node = root.getChildAt(i) as RunNode
            if (node.run.status.isInProgress && tree.isExpanded(TreePath(node.path))) {
                loadJobs(node, forceRefresh = true)
            }
        }
    }

    /** Opens the CI tab on the run requested by a VCS-log column click, paging to it if needed. */
    fun revealPending() {
        val target = coordinator.pending() ?: return
        if (repoCombo.itemCount == 0) return // contexts not resolved yet; retried once runs load
        val match = (0 until repoCombo.itemCount).map { repoCombo.getItemAt(it) }.firstOrNull { it.matches(target) }
        if (match != null && match != repoCombo.selectedItem) {
            repoCombo.selectedItem = match // fires reloadRuns; its applyRunsPage re-enters here
            return
        }
        val node = runNodesByIndex.values.firstOrNull {
            it.run.commitSha.equals(target.sha, ignoreCase = true) || it.run.commitSha.startsWith(target.sha, ignoreCase = true)
        }
        when {
            node != null -> {
                coordinator.clearPending()
                val path = TreePath(node.path)
                tree.selectionPath = path
                tree.scrollPathToVisible(path)
            }
            runsHasMore && !loadingRuns -> {
                // Not loaded yet — page further; applyRunsPage will call revealPending again.
                (repoCombo.selectedItem as? ForgejoRepoContext)?.let { loadRuns(it, runsPagesLoaded + 1, LoadMode.APPEND) }
            }
            else -> coordinator.clearPending() // exhausted; leave the tab open on this repo
        }
    }

    /** True if [ctx] still identifies the selected repo (by value, not instance — accounts may differ). */
    private fun isCurrentRepo(ctx: ForgejoRepoContext): Boolean {
        val selected = repoCombo.selectedItem as? ForgejoRepoContext ?: return false
        return selected.owner == ctx.owner &&
            selected.repo == ctx.repo &&
            selected.account.server.toString() == ctx.account.server.toString()
    }

    private fun ForgejoRepoContext.matches(target: ForgejoActionsCoordinator.Target): Boolean =
        account.server.toString().equals(target.server, ignoreCase = true) &&
            owner.equals(target.owner, ignoreCase = true) &&
            repo.equals(target.repo, ignoreCase = true)

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

    private fun resetState() {
        root.removeAllChildren()
        treeModel.nodeStructureChanged(root)
        runNodesByIndex.clear()
        runsPagesLoaded = 0
        runsTotalCount = 0
        runsHasMore = false
        synchronized(taskLock) {
            tasksById.clear()
            taskPagesFetched = 0
            allTasksLoaded = false
        }
    }

    /** EDT-only. */
    private fun setRootEmpty(message: String) {
        resetState()
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
        coordinator.unregister(this)
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

    private class RunNode(var run: ForgejoRun) : DefaultMutableTreeNode() {
        var jobsLoaded = false

        @Volatile
        var jobsLoading = false

        override fun toString(): String = "run:${run.index}"
    }

    private class TaskNode(val task: ForgejoTask) : DefaultMutableTreeNode(task) {
        override fun toString(): String = "job:${task.id}"
    }

    private class MessageNode(val text: String) : DefaultMutableTreeNode(text) {
        override fun toString(): String = text
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
                    if (run.branch.isNotEmpty()) append("  ${run.branch}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    appendTime(run.status, run.startedAtMillis, run.durationMillis)
                }

                is TaskNode -> {
                    val task = value.task
                    icon = task.status.icon()
                    append(task.name)
                    if (task.attempts > 1) {
                        append("  ${ForgejoBundle.message("actions.attempts", task.attempts)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                    appendTime(task.status, task.startedAtMillis, task.durationMillis)
                }

                is MessageNode -> append(value.text, SimpleTextAttributes.GRAYED_ATTRIBUTES)
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
        const val RUNS_PAGE_SIZE = 20
        const val TASKS_PAGE_SIZE = 50
        const val MAX_RETRIES = 3
        const val RETRY_DELAY_MS = 1200
        val LOAD_MORE_THRESHOLD: Int get() = JBUI.scale(80)
    }
}
