package octris.forgejo.api

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.intellij.openapi.diagnostic.thisLogger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.OffsetDateTime

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

    /** Combined commit status (state + a link to the run/job), or null if unavailable / on error. */
    fun getCommitStatus(
        server: String,
        token: String,
        owner: String,
        repo: String,
        sha: String,
    ): ForgejoCommitStatusInfo? = runCatching {
        val dto = gson.fromJson(get(server, token, "repos/$owner/$repo/commits/$sha/status"), CombinedStatusDto::class.java)
        val state = ForgejoCommitState.fromState(dto?.state) ?: return@runCatching null
        val target = dto?.statuses?.firstOrNull { !it.targetUrl.isNullOrBlank() }?.targetUrl
        ForgejoCommitStatusInfo(state, absoluteUrl(server, target))
    }.getOrElse {
        thisLogger().debug("Failed to fetch Forgejo commit status for $owner/$repo@$sha", it)
        null
    }

    /**
     * Lists recent workflow runs (with their jobs) for the repo, newest first.
     *
     * Derived from the lightweight `/actions/tasks` endpoint — the `/actions/runs` payload is far
     * heavier (full event payload + repository per run). Tasks are grouped by run number into a run
     * whose status is aggregated from its jobs.
     */
    fun listRuns(server: String, token: String, owner: String, repo: String, limit: Int = 50): Result<List<ForgejoRun>> = runCatching {
        val dto = gson.fromJson(get(server, token, "repos/$owner/$repo/actions/tasks?limit=$limit"), TasksDto::class.java)
        dto?.workflowRuns.orEmpty()
            .groupBy { it.runNumber }
            .map { (runNumber, tasks) -> buildRun(runNumber, tasks) }
            .sortedByDescending { it.index }
    }

    private fun buildRun(runNumber: Long, tasks: List<TaskDto>): ForgejoRun {
        val first = tasks.first()
        val jobs = tasks.map { it.toJob() }
        val statuses = jobs.map { it.status }
        val started = tasks.mapNotNull { parseMillis(it.runStartedAt ?: it.createdAt) }.minOrNull() ?: 0L
        val end = tasks.mapNotNull { parseMillis(it.updatedAt) }.maxOrNull() ?: 0L
        val running = statuses.any { it.isInProgress }
        return ForgejoRun(
            index = runNumber,
            workflow = first.workflowId.orEmpty(),
            branch = first.headBranch.orEmpty(),
            title = first.displayTitle.orEmpty(),
            commitSha = first.headSha.orEmpty(),
            status = aggregateStatus(statuses),
            htmlUrl = first.url.orEmpty(),
            startedAtMillis = started,
            durationMillis = if (running || started == 0L || end <= started) 0 else end - started,
            jobs = jobs,
        )
    }

    /** Resolves a possibly-relative Forgejo URL (e.g. status `target_url`) against the server. */
    private fun absoluteUrl(server: String, path: String?): String? {
        if (path.isNullOrBlank()) return null
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val base = server.trim().removeSuffix("/").let { if ("://" in it) it else "https://$it" }
        return base + path
    }

    private fun get(server: String, token: String, path: String): String {
        val raw = server.trim().removeSuffix("/")
        require(raw.isNotEmpty()) { "Server URL is not configured" }
        require(token.isNotEmpty()) { "Access token is not configured" }
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

    private data class CombinedStatusDto(
        val state: String? = null,
        val statuses: List<CommitStatusDto>? = null,
    )

    private data class CommitStatusDto(@SerializedName("target_url") val targetUrl: String? = null)

    private data class TasksDto(@SerializedName("workflow_runs") val workflowRuns: List<TaskDto>? = null)

    private data class TaskDto(
        val id: Long = 0,
        val name: String? = null,
        val status: String? = null,
        @SerializedName("run_number") val runNumber: Long = 0,
        val url: String? = null,
        @SerializedName("workflow_id") val workflowId: String? = null,
        @SerializedName("head_branch") val headBranch: String? = null,
        @SerializedName("head_sha") val headSha: String? = null,
        @SerializedName("display_title") val displayTitle: String? = null,
        @SerializedName("created_at") val createdAt: String? = null,
        @SerializedName("updated_at") val updatedAt: String? = null,
        @SerializedName("run_started_at") val runStartedAt: String? = null,
    ) {
        fun toJob(): ForgejoTask {
            val jobStatus = ForgejoActionStatus.fromString(status)
            val started = parseMillis(createdAt) ?: 0L
            val end = parseMillis(updatedAt) ?: 0L
            return ForgejoTask(
                id = id,
                name = name.orEmpty(),
                status = jobStatus,
                runUrl = url.orEmpty(),
                startedAtMillis = started,
                durationMillis = if (jobStatus.isInProgress || started == 0L || end <= started) 0 else end - started,
            )
        }
    }

    private companion object {
        val gson = Gson()
    }
}

private fun aggregateStatus(statuses: List<ForgejoActionStatus>): ForgejoActionStatus = when {
    statuses.isEmpty() -> ForgejoActionStatus.UNKNOWN
    statuses.any { it == ForgejoActionStatus.FAILURE } -> ForgejoActionStatus.FAILURE
    statuses.any { it == ForgejoActionStatus.RUNNING } -> ForgejoActionStatus.RUNNING
    statuses.any { it == ForgejoActionStatus.WAITING || it == ForgejoActionStatus.BLOCKED } -> ForgejoActionStatus.WAITING
    statuses.any { it == ForgejoActionStatus.CANCELLED } -> ForgejoActionStatus.CANCELLED
    statuses.all { it == ForgejoActionStatus.SKIPPED } -> ForgejoActionStatus.SKIPPED
    statuses.all { it == ForgejoActionStatus.SUCCESS || it == ForgejoActionStatus.SKIPPED } -> ForgejoActionStatus.SUCCESS
    else -> ForgejoActionStatus.UNKNOWN
}

private fun parseMillis(text: String?): Long? =
    text?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

/** Authenticated Forgejo user info. */
data class ForgejoUser(val login: String, val avatarUrl: String?)

/** Combined commit status: the overall state plus a link to the run/job page (if any). */
data class ForgejoCommitStatusInfo(val state: ForgejoCommitState, val url: String?)
