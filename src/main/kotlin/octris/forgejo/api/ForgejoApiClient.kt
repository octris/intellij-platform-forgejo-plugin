package octris.forgejo.api

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.intellij.openapi.diagnostic.thisLogger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Minimal HTTP client for the Forgejo REST API (docs: https://git.octr.is/api/swagger).
 *
 * Every method performs blocking network I/O and MUST be called off the EDT. Server and token
 * are passed explicitly so callers (e.g. the settings "Test connection" button) can validate
 * values that aren't saved yet.
 */
class ForgejoApiClient {

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /** Resolves the authenticated user (login + avatar) via `GET /user`, validating the token. */
    fun getUser(server: String, token: String): Result<ForgejoUser> = runCatching {
        val dto = gson.fromJson(get(server, token, "user"), UserDto::class.java)
        val login = dto?.login
        require(!login.isNullOrBlank()) { "Response did not contain a user login" }
        ForgejoUser(login, dto.avatarUrl?.takeIf { it.isNotBlank() })
    }

    /** Combined commit-status state for a commit, or null if unavailable / on error. */
    fun getCombinedCommitState(
        server: String,
        token: String,
        owner: String,
        repo: String,
        sha: String,
    ): ForgejoCommitState? = runCatching {
        val dto = gson.fromJson(get(server, token, "repos/$owner/$repo/commits/$sha/status"), CombinedStatusDto::class.java)
        ForgejoCommitState.fromState(dto?.state)
    }.getOrElse {
        thisLogger().debug("Failed to fetch Forgejo commit status for $owner/$repo@$sha", it)
        null
    }

    private fun get(server: String, token: String, path: String): String {
        val raw = server.trim().removeSuffix("/")
        require(raw.isNotEmpty()) { "Server URL is not configured" }
        require(token.isNotEmpty()) { "Access token is not configured" }
        // Be forgiving if the user omits the scheme.
        val base = if ("://" in raw) raw else "https://$raw"
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$base/api/v1/$path"))
            .header("Authorization", "token $token")
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(15))
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) { "HTTP ${response.statusCode()} from ${request.uri()}" }
        return response.body()
    }

    private data class UserDto(
        val login: String? = null,
        @SerializedName("avatar_url") val avatarUrl: String? = null,
    )

    private data class CombinedStatusDto(val state: String? = null)

    private companion object {
        val gson = Gson()
    }
}

/** Authenticated Forgejo user info. */
data class ForgejoUser(val login: String, val avatarUrl: String?)
