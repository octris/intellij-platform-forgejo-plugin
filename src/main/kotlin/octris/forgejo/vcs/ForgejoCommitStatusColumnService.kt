package octris.forgejo.vcs

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.vcs.log.data.util.VcsCommitsDataLoader
import com.intellij.vcs.log.ui.table.column.util.VcsLogExternalStatusColumnService
import kotlinx.coroutines.CoroutineScope

/**
 * Application-level service backing the Forgejo commit-status column. The platform base class
 * handles caching, scheduling for visible rows, and repainting; we only supply the coroutine scope
 * and a per-project data loader.
 */
@Service(Service.Level.APP)
class ForgejoCommitStatusColumnService(private val cs: CoroutineScope) :
    VcsLogExternalStatusColumnService<ForgejoCommitStatus>() {

    override val scope: CoroutineScope
        get() = cs

    override fun getDataLoader(project: Project): VcsCommitsDataLoader<ForgejoCommitStatus> =
        ForgejoCommitStatusLoader(project)
}
