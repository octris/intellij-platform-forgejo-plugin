package octris.forgejo.vcs

import com.intellij.collaboration.ui.icon.CIBuildStatusIcons
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.vcs.log.ui.frame.VcsCommitExternalStatusPresentation
import octris.forgejo.actions.ForgejoActionsCoordinator
import octris.forgejo.api.ForgejoCommitState
import java.awt.event.InputEvent
import javax.swing.Icon

/**
 * Renders a Forgejo CI state in the VCS log using the shared [CIBuildStatusIcons] (icon-only, like
 * GitHub). Clicking the cell opens the "Forgejo CI" tool window focused on that commit's run.
 */
class ForgejoCommitStatusPresentation(
    private val project: Project,
    private val status: ForgejoCommitStatus.Loaded,
) : VcsCommitExternalStatusPresentation.Clickable {

    override val icon: Icon
        get() = when (status.state) {
            ForgejoCommitState.SUCCESS -> CIBuildStatusIcons.success
            ForgejoCommitState.FAILURE -> CIBuildStatusIcons.failed
            ForgejoCommitState.ERROR -> CIBuildStatusIcons.failed
            ForgejoCommitState.PENDING -> CIBuildStatusIcons.pending
            ForgejoCommitState.RUNNING -> CIBuildStatusIcons.inProgress
            ForgejoCommitState.WARNING -> CIBuildStatusIcons.warning
        }

    override val text: String
        get() = ""

    override fun clickEnabled(e: InputEvent?): Boolean = status.commitSha.isNotEmpty()

    override fun onClick(e: InputEvent?): Boolean {
        project.service<ForgejoActionsCoordinator>().showCommit(status.context, status.commitSha)
        return true
    }
}
