package octris.forgejo.vcs

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.ui.EmptyIcon
import com.intellij.vcs.log.ui.frame.VcsCommitExternalStatusPresentation
import com.intellij.vcs.log.ui.frame.VcsCommitExternalStatusProvider
import com.intellij.vcs.log.ui.table.column.util.VcsLogExternalStatusColumnService
import octris.forgejo.ForgejoBundle
import com.intellij.openapi.components.service
import octris.forgejo.settings.ForgejoAccountManager
import javax.swing.Icon

/**
 * Registers the "Forgejo CI" column in the VCS log via the platform's external-status framework
 * (the same one the GitHub plugin uses), so rendering, caching, loading and repaint are handled
 * natively and match the GitHub plugin's look and feel.
 */
class ForgejoCommitStatusProvider : VcsCommitExternalStatusProvider.WithColumn<ForgejoCommitStatus>() {

    override val id: String
        get() = ID

    override val columnName: String
        get() = ForgejoBundle.message("column.name")

    override val isColumnEnabledByDefault: Boolean
        get() = true

    override fun isColumnAvailable(project: Project, roots: Collection<VirtualFile>): Boolean =
        service<ForgejoAccountManager>().accountsState.value.isNotEmpty()

    override fun getExternalStatusColumnService(): VcsLogExternalStatusColumnService<ForgejoCommitStatus> =
        service<ForgejoCommitStatusColumnService>()

    override fun getStubStatus(): ForgejoCommitStatus = ForgejoCommitStatus.NotLoaded

    override fun getPresentation(project: Project, status: ForgejoCommitStatus): VcsCommitExternalStatusPresentation =
        when (status) {
            is ForgejoCommitStatus.Loaded -> ForgejoCommitStatusPresentation(status.state, status.url)
            ForgejoCommitStatus.NotLoaded -> EMPTY_PRESENTATION
        }

    private companion object {
        const val ID = "Forgejo.CommitStatus"

        val EMPTY_PRESENTATION = object : VcsCommitExternalStatusPresentation {
            override val icon: Icon = EmptyIcon.ICON_16
            override val text: String = ""
        }
    }
}
