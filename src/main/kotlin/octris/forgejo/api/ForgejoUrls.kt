package octris.forgejo.api

import java.net.URI

/** URL helpers shared by account management and Git-remote resolution. */
object ForgejoUrls {

    /** owner/repo parsed from a Git remote, with the host it lives on. */
    data class RemoteRepo(val host: String, val owner: String, val repo: String)

    /** Host of a server URL (scheme optional), or null if it can't be parsed. */
    fun hostOf(url: String): String? {
        val s = url.trim().ifEmpty { return null }
        val withScheme = if ("://" in s) s else "https://$s"
        return runCatching { URI(withScheme).host }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** Parses a Git remote URL (ssh/https/scp form) into host + owner/repo, or null. */
    fun parseRemote(url: String): RemoteRepo? {
        val trimmed = url.trim()
        val (host, path) = when {
            "://" in trimmed -> {
                val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
                (uri.host ?: return null) to uri.path.orEmpty()
            }
            // scp-like: git@host:owner/repo(.git)
            else -> {
                val match = SCP_LIKE.find(trimmed) ?: return null
                match.groupValues[1] to match.groupValues[2]
            }
        }
        val segments = path.trim('/').removeSuffix(".git").split('/').filter { it.isNotEmpty() }
        if (segments.size < 2) return null
        return RemoteRepo(host, owner = segments[segments.size - 2], repo = segments.last())
    }

    private val SCP_LIKE = Regex("""^[^/@]+@([^:/]+):(.+)$""")
}
