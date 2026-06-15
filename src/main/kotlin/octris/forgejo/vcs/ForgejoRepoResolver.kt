package octris.forgejo.vcs

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import git4idea.repo.GitRepositoryManager
import octris.forgejo.api.ForgejoUrls
import octris.forgejo.settings.ForgejoAccount
import octris.forgejo.settings.ForgejoAccountManager
import octris.forgejo.settings.ForgejoDefaultAccountHolder

/** The Forgejo account + owner/repo a Git remote maps to. */
data class ForgejoRepoContext(val account: ForgejoAccount, val owner: String, val repo: String)

/**
 * Maps a project's Git remotes to the Forgejo accounts/repos they belong to. A project (or root) can
 * have several Forgejo remotes; results are ordered with the project's default account first. Must be
 * called off the EDT (reads Git state).
 */
object ForgejoRepoResolver {

    /** All Forgejo contexts across every repository in the project (deduped, default account first). */
    fun allContexts(project: Project): List<ForgejoRepoContext> {
        val (accounts, default) = accountsAndDefault(project) ?: return emptyList()
        val all = GitRepositoryManager.getInstance(project).repositories
            .flatMap { contexts(it.remotes.flatMap { remote -> remote.urls }, accounts, default) }
        return all.distinct().sortedByDescending { it.account == default }
    }

    /** Forgejo contexts for a single VCS [root] (deduped, default account first). */
    fun contextsFor(project: Project, root: VirtualFile): List<ForgejoRepoContext> {
        val (accounts, default) = accountsAndDefault(project) ?: return emptyList()
        val repository = GitRepositoryManager.getInstance(project).getRepositoryForRootQuick(root) ?: return emptyList()
        return contexts(repository.remotes.flatMap { it.urls }, accounts, default)
            .distinct()
            .sortedByDescending { it.account == default }
    }

    private fun contexts(urls: List<String>, accounts: Set<ForgejoAccount>, default: ForgejoAccount?): List<ForgejoRepoContext> =
        urls.mapNotNull { ForgejoUrls.parseRemote(it) }
            .mapNotNull { remote -> accountForHost(accounts, default, remote.host)?.let { ForgejoRepoContext(it, remote.owner, remote.repo) } }

    private fun accountForHost(accounts: Set<ForgejoAccount>, default: ForgejoAccount?, host: String): ForgejoAccount? {
        val matches = accounts.filter { ForgejoUrls.hostOf(it.server.toString()).equals(host, ignoreCase = true) }
        return matches.firstOrNull { it == default } ?: matches.firstOrNull()
    }

    private fun accountsAndDefault(project: Project): Pair<Set<ForgejoAccount>, ForgejoAccount?>? {
        val accounts = service<ForgejoAccountManager>().accountsState.value
        if (accounts.isEmpty()) return null
        return accounts to project.service<ForgejoDefaultAccountHolder>().account
    }
}
