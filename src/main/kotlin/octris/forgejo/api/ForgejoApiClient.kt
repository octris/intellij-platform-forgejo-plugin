package octris.forgejo.api

import com.intellij.openapi.diagnostic.thisLogger
import octris.forgejo.settings.ForgejoCredentials
import octris.forgejo.settings.ForgejoSettings

/**
 * Thin client for the Forgejo REST API.
 *
 * STUB: [fetchCommitStatus] returns [ForgejoCommitStatus.UNKNOWN] until the real call is
 * implemented. The combined commit status lives at:
 *
 *     GET {server}/api/v1/repos/{owner}/{repo}/commits/{sha}/status
 *     Authorization: token {personal-access-token}
 *
 * The response's `state` field ("success" | "failure" | "pending" | "error") maps via
 * [ForgejoCommitStatus.fromState]. Per-workflow Forgejo Actions run progress is available
 * under the Actions endpoints (`/repos/{owner}/{repo}/actions/...`) once enabled on the
 * instance — wire that in here when extending beyond a single combined status.
 */
class ForgejoApiClient {

    fun fetchCommitStatus(owner: String, repo: String, sha: String): ForgejoCommitStatus {
        val server = ForgejoSettings.instance.server
        val token = ForgejoCredentials.getToken()
        if (server.isBlank() || token.isNullOrBlank()) {
            thisLogger().debug("Forgejo not configured; skipping status fetch for $sha")
            return ForgejoCommitStatus.UNKNOWN
        }

        // TODO: perform the authenticated HTTP GET against
        //   "$server/api/v1/repos/$owner/$repo/commits/$sha/status"
        // and return ForgejoCommitStatus.fromState(response.state).
        thisLogger().debug("TODO: fetch Forgejo commit status for $owner/$repo@$sha")
        return ForgejoCommitStatus.UNKNOWN
    }
}
