package octris.forgejo.actions

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import octris.forgejo.vcs.ForgejoRepoContext

/**
 * Bridges the VCS-log "Forgejo CI" column to the tool window: clicking a commit's status opens the
 * tab and reveals that commit's run. The active [ForgejoActionsPanel] registers itself here; a click
 * stores a pending [Target], activates the tool window, and the panel consumes it once its runs load
 * (switching to the right repo first if needed).
 */
@Service(Service.Level.PROJECT)
class ForgejoActionsCoordinator(private val project: Project) {

    /** A request to reveal the run for [sha] in the repo identified by [server]/[owner]/[repo]. */
    data class Target(val server: String, val owner: String, val repo: String, val sha: String)

    @Volatile
    private var pending: Target? = null
    private var panel: ForgejoActionsPanel? = null

    /** Called from the VCS-log column (EDT): opens the CI tab focused on [sha]. */
    fun showCommit(context: ForgejoRepoContext, sha: String) {
        pending = Target(context.account.server.toString(), context.owner, context.repo, sha)
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
        // activate() ensures the content (hence the panel) is created, then runs the callback.
        toolWindow.activate({ panel?.revealPending() }, true, true)
    }

    fun register(panel: ForgejoActionsPanel) {
        this.panel = panel
        panel.revealPending()
    }

    fun unregister(panel: ForgejoActionsPanel) {
        if (this.panel === panel) this.panel = null
    }

    fun pending(): Target? = pending

    fun clearPending() {
        pending = null
    }

    companion object {
        const val TOOL_WINDOW_ID = "Forgejo CI"
    }
}
