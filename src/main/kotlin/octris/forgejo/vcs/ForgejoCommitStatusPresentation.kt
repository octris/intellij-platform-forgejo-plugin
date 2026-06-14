package octris.forgejo.vcs

import com.intellij.collaboration.ui.icon.CIBuildStatusIcons
import com.intellij.vcs.log.ui.frame.VcsCommitExternalStatusPresentation
import octris.forgejo.api.ForgejoCommitState
import javax.swing.Icon

/**
 * Renders a Forgejo CI state in the VCS log using the same icon set the GitHub/GitLab plugins use
 * ([CIBuildStatusIcons]). Icon-only, matching the GitHub plugin's compact presentation.
 */
class ForgejoCommitStatusPresentation(private val state: ForgejoCommitState) : VcsCommitExternalStatusPresentation {

    override val icon: Icon
        get() = when (state) {
            ForgejoCommitState.SUCCESS -> CIBuildStatusIcons.success
            ForgejoCommitState.FAILURE -> CIBuildStatusIcons.failed
            ForgejoCommitState.ERROR -> CIBuildStatusIcons.failed
            ForgejoCommitState.PENDING -> CIBuildStatusIcons.pending
            ForgejoCommitState.RUNNING -> CIBuildStatusIcons.inProgress
            ForgejoCommitState.WARNING -> CIBuildStatusIcons.warning
        }

    override val text: String
        get() = ""
}
