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
     * Fetches one page of the repo's workflow runs from `/actions/runs`, newest first, plus the
     * server's total run count. This is the run list the CI tab paginates by scroll: each run object
     * carries run-level status/branch/title/commit/timing directly, so no per-task aggregation is
     * needed. (Forgejo has no per-run jobs endpoint, so a run's jobs are loaded lazily on expand from
     * the bulk [listTasksPage] feed.)
     */
    fun listRunsPage(
        server: String,
        token: String,
        owner: String,
        repo: String,
        page: Int,
        limit: Int,
    ): Result<ForgejoRunsPage> = runCatching {
        val dto = gson.fromJson(get(server, token, "repos/$owner/$repo/actions/runs?limit=$limit&page=$page"), RunsDto::class.java)
        ForgejoRunsPage(dto?.workflowRuns.orEmpty().map { it.toRun() }, dto?.totalCount ?: 0)
    }

    /**
     * Fetches one page of the repo's Actions tasks (jobs) from `/actions/tasks`, newest first, plus
     * the server's total task count. Used to lazily resolve a run's jobs: the feed isn't filterable by
     * run, so callers page it (newest-first) and pick out the tasks whose `run_number` matches.
     */
    fun listTasksPage(
        server: String,
        token: String,
        owner: String,
        repo: String,
        page: Int,
        limit: Int,
    ): Result<ForgejoTasksPage> = runCatching {
        val dto = gson.fromJson(get(server, token, "repos/$owner/$repo/actions/tasks?limit=$limit&page=$page"), TasksDto::class.java)
        ForgejoTasksPage(dto?.workflowRuns.orEmpty().map { it.toRaw() }, dto?.totalCount ?: 0)
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

    private data class TasksDto(
        @SerializedName("total_count") val totalCount: Int = 0,
        @SerializedName("workflow_runs") val workflowRuns: List<TaskDto>? = null,
    )

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
        fun toRaw(): ForgejoTaskRaw = ForgejoTaskRaw(
            id = id,
            name = name,
            status = status,
            runNumber = runNumber,
            url = url,
            workflowId = workflowId,
            headBranch = headBranch,
            headSha = headSha,
            displayTitle = displayTitle,
            createdAt = createdAt,
            updatedAt = updatedAt,
            runStartedAt = runStartedAt,
        )
    }

    private data class RunsDto(
        @SerializedName("total_count") val totalCount: Int = 0,
        @SerializedName("workflow_runs") val workflowRuns: List<RunDto>? = null,
    )

    private data class RunDto(
        @SerializedName("index_in_repo") val indexInRepo: Long = 0,
        val status: String? = null,
        val prettyref: String? = null,
        @SerializedName("commit_sha") val commitSha: String? = null,
        val title: String? = null,
        @SerializedName("workflow_id") val workflowId: String? = null,
        @SerializedName("html_url") val htmlUrl: String? = null,
        val started: String? = null,
        val stopped: String? = null,
        val duration: Long = 0, // nanoseconds
    ) {
        fun toRun(): ForgejoRun {
            val runStatus = ForgejoActionStatus.fromString(status)
            val startedMs = parseMillis(started) ?: 0L
            val stoppedMs = parseMillis(stopped) ?: 0L
            val durationMs = when {
                duration > 0 -> duration / 1_000_000
                startedMs > 0 && stoppedMs > startedMs -> stoppedMs - startedMs
                else -> 0L
            }
            return ForgejoRun(
                index = indexInRepo,
                workflow = workflowId.orEmpty(),
                branch = prettyref.orEmpty(),
                title = title.orEmpty(),
                commitSha = commitSha.orEmpty(),
                status = runStatus,
                htmlUrl = htmlUrl.orEmpty(),
                startedAtMillis = startedMs,
                durationMillis = if (runStatus.isInProgress) 0 else durationMs,
                jobs = emptyList(), // jobs are loaded lazily on expand
            )
        }
    }

    private companion object {
        val gson = Gson()
    }
}

/** Maps a raw Actions task to a job (a run's child row in the CI tab). */
fun ForgejoTaskRaw.toJob(): ForgejoTask {
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

private fun parseMillis(text: String?): Long? =
    text?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

/** Authenticated Forgejo user info. */
data class ForgejoUser(val login: String, val avatarUrl: String?)

/** Combined commit status: the overall state plus a link to the run/job page (if any). */
data class ForgejoCommitStatusInfo(val state: ForgejoCommitState, val url: String?)

/** One page of workflow runs plus the server's reported total run count (drives run-list paging). */
data class ForgejoRunsPage(val runs: List<ForgejoRun>, val totalCount: Int)

/** One page of raw Actions tasks plus the server's reported total task count. */
data class ForgejoTasksPage(val tasks: List<ForgejoTaskRaw>, val totalCount: Int)
