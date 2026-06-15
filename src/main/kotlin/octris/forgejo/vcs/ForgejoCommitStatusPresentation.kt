package octris.forgejo.vcs

import com.intellij.collaboration.ui.icon.CIBuildStatusIcons
import com.intellij.ide.BrowserUtil
import com.intellij.vcs.log.ui.frame.VcsCommitExternalStatusPresentation
import octris.forgejo.api.ForgejoCommitState
import java.awt.event.InputEvent
import javax.swing.Icon

/**
 * Renders a Forgejo CI state in the VCS log using the shared [CIBuildStatusIcons] (icon-only, like
 * GitHub). When a run/job URL is known, the cell is clickable and opens it in the browser.
 */
class ForgejoCommitStatusPresentation(
    private val state: ForgejoCommitState,
    private val url: String?,
) : VcsCommitExternalStatusPresentation.Clickable {

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

    override fun clickEnabled(e: InputEvent?): Boolean = url != null

    override fun onClick(e: InputEvent?): Boolean {
        url?.let { BrowserUtil.browse(it) }
        return true
    }
}
