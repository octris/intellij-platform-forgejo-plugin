package octris.forgejo.vcs

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import octris.forgejo.api.ForgejoApiClient
import octris.forgejo.api.ForgejoCommitStatus
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-project cache of Forgejo commit statuses, keyed by full commit hash.
 *
 * The VCS log renderer must stay on the EDT and cannot block, so [getStatus] only ever
 * reads the in-memory cache and, on a miss, schedules a background fetch. The result is
 * cached for subsequent repaints.
 *
 * TODO: once [ForgejoApiClient] returns real data, trigger a repaint of the log when a
 * fetch completes (e.g. via VcsProjectLog refresh or a VcsLogCustomColumnListener), and
 * add a "Refresh" action that clears [cache] so statuses can update.
 */
@Service(Service.Level.PROJECT)
class ForgejoCommitStatusService(private val project: Project) {

    private val client = ForgejoApiClient()
    private val cache = ConcurrentHashMap<String, ForgejoCommitStatus>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** Non-blocking. Returns the cached status, scheduling a background fetch on a miss. */
    fun getStatus(hash: String): ForgejoCommitStatus {
        cache[hash]?.let { return it }
        scheduleFetch(hash)
        return ForgejoCommitStatus.UNKNOWN
    }

    private fun scheduleFetch(hash: String) {
        if (!inFlight.add(hash)) return
        ApplicationManager.getApplication().executeOnPooledThread(Runnable {
            try {
                // TODO: resolve owner/repo from the commit's VCS root remote URL.
                cache[hash] = client.fetchCommitStatus(owner = "", repo = "", sha = hash)
            } finally {
                inFlight.remove(hash)
            }
        })
    }

    companion object {
        fun getInstance(project: Project): ForgejoCommitStatusService = project.service()
    }
}
