package octris.forgejo.vcs

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import git4idea.repo.GitRepositoryManager
import octris.forgejo.api.ForgejoUrls

/**
 * Resolves the Git remotes for a VCS root, parsed into host/owner/repo so the caller can match them
 * against configured Forgejo accounts. Must be called off the EDT (reads Git repository state).
 */
object ForgejoRepoResolver {

    fun remotesFor(project: Project, root: VirtualFile): List<ForgejoUrls.RemoteRepo> {
        val repository = GitRepositoryManager.getInstance(project).getRepositoryForRootQuick(root) ?: return emptyList()
        return repository.remotes.asSequence()
            .flatMap { it.urls.asSequence() }
            .mapNotNull { ForgejoUrls.parseRemote(it) }
            .distinct()
            .toList()
    }
}
