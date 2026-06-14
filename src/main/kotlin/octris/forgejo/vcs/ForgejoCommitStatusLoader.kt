package octris.forgejo.vcs

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcs.log.CommitId
import com.intellij.vcs.log.data.util.VcsCommitsDataLoader
import kotlinx.coroutines.runBlocking
import octris.forgejo.api.ForgejoApiClient
import octris.forgejo.api.ForgejoUrls
import octris.forgejo.settings.ForgejoAccount
import octris.forgejo.settings.ForgejoAccountManager
import octris.forgejo.settings.ForgejoDefaultAccountHolder

/**
 * Loads Forgejo commit statuses for the VCS log's visible commits. For each commit's root it finds
 * the configured account whose host matches a Git remote (preferring the project default), fetches
 * the combined status off the EDT, and reports results via [onChange] for the platform to cache.
 */
class ForgejoCommitStatusLoader(private val project: Project) : VcsCommitsDataLoader<ForgejoCommitStatus> {

    private val client = ForgejoApiClient()

    @Volatile
    private var disposed = false

    override fun loadData(commits: List<CommitId>, onChange: (Map<CommitId, ForgejoCommitStatus>) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val manager = service<ForgejoAccountManager>()
            val accounts = manager.accountsState.value
            if (disposed || accounts.isEmpty()) return@executeOnPooledThread
            val default = project.service<ForgejoDefaultAccountHolder>().account

            val resolvedByRoot = HashMap<VirtualFile, Resolved?>()
            val result = HashMap<CommitId, ForgejoCommitStatus>()
            for (commit in commits) {
                if (disposed) return@executeOnPooledThread
                val resolved = resolvedByRoot.getOrPut(commit.root) { resolve(manager, commit.root, accounts, default) } ?: continue
                val token = resolved.token ?: continue
                val state = client.getCombinedCommitState(
                    resolved.account.server.toString(), token, resolved.owner, resolved.repo, commit.hash.asString(),
                ) ?: continue
                result[commit] = ForgejoCommitStatus.Loaded(state)
            }
            if (result.isNotEmpty() && !disposed) {
                ApplicationManager.getApplication().invokeLater({ onChange(result) }, ModalityState.any())
            }
        }
    }

    private data class Resolved(val account: ForgejoAccount, val owner: String, val repo: String, val token: String?)

    private fun resolve(manager: ForgejoAccountManager, root: VirtualFile, accounts: Set<ForgejoAccount>, default: ForgejoAccount?): Resolved? {
        for (remote in ForgejoRepoResolver.remotesFor(project, root)) {
            val account = findAccountForHost(accounts, default, remote.host) ?: continue
            val token = runBlocking { manager.findCredentials(account) }
            return Resolved(account, remote.owner, remote.repo, token)
        }
        return null
    }

    private fun findAccountForHost(accounts: Set<ForgejoAccount>, default: ForgejoAccount?, host: String): ForgejoAccount? {
        val matches = accounts.filter { ForgejoUrls.hostOf(it.server.toString()).equals(host, ignoreCase = true) }
        return matches.firstOrNull { it == default } ?: matches.firstOrNull()
    }

    override fun dispose() {
        disposed = true
    }
}
