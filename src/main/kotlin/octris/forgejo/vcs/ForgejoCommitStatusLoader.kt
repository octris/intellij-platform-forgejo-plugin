package octris.forgejo.vcs

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcs.log.CommitId
import com.intellij.vcs.log.data.util.VcsCommitsDataLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import octris.forgejo.api.ForgejoApiClient
import octris.forgejo.settings.ForgejoAccount
import octris.forgejo.settings.ForgejoAccountManager

/**
 * Loads Forgejo commit statuses for the VCS log's visible commits. A commit's root may have several
 * Forgejo remotes; we try each (default account first) and show the status from whichever repo has
 * one. Commits are fetched concurrently (bounded) off the EDT so the log updates quickly.
 */
class ForgejoCommitStatusLoader(private val project: Project) : VcsCommitsDataLoader<ForgejoCommitStatus> {

    private val client = ForgejoApiClient()

    @Volatile
    private var disposed = false

    override fun loadData(commits: List<CommitId>, onChange: (Map<CommitId, ForgejoCommitStatus>) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            if (disposed || commits.isEmpty()) return@executeOnPooledThread
            val result = runBlocking { fetchAll(commits) }
            if (result.isNotEmpty() && !disposed) {
                ApplicationManager.getApplication().invokeLater({ onChange(result) }, ModalityState.any())
            }
        }
    }

    private suspend fun fetchAll(commits: List<CommitId>): Map<CommitId, ForgejoCommitStatus> {
        // Resolve contexts per root (cheap, no network) and pre-fetch each account's token once.
        val contextsByRoot = commits.map { it.root }.distinct()
            .associateWith { ForgejoRepoResolver.contextsFor(project, it) }
        val manager = service<ForgejoAccountManager>()
        val tokens = HashMap<ForgejoAccount, String?>()
        for (account in contextsByRoot.values.flatten().map { it.account }.distinct()) {
            tokens[account] = manager.findCredentials(account)
        }

        val semaphore = Semaphore(MAX_CONCURRENCY)
        return coroutineScope {
            commits.map { commit ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        if (disposed) return@withPermit null
                        val status = fetchStatus(contextsByRoot[commit.root].orEmpty(), tokens, commit.hash.asString())
                        status?.let { commit to it }
                    }
                }
            }.awaitAll().filterNotNull().toMap()
        }
    }

    /** Tries each repo (default account first); returns the status from the first that has one. */
    private fun fetchStatus(contexts: List<ForgejoRepoContext>, tokens: Map<ForgejoAccount, String?>, sha: String): ForgejoCommitStatus? {
        for (context in contexts) {
            val token = tokens[context.account] ?: continue
            val info = client.getCommitStatus(context.account.server.toString(), token, context.owner, context.repo, sha)
                ?: continue
            return ForgejoCommitStatus.Loaded(info.state, info.url)
        }
        return null
    }

    override fun dispose() {
        disposed = true
    }

    private companion object {
        const val MAX_CONCURRENCY = 8
    }
}
