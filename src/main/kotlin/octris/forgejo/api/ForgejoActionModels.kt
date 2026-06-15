package octris.forgejo.api

/**
 * A Forgejo Actions workflow run with its jobs. Synthesized from the lightweight `/actions/tasks`
 * endpoint, so there's no trigger-user/event payload; [status] is aggregated from the jobs.
 */
data class ForgejoRun(
    val index: Long,
    val workflow: String,
    val branch: String,
    val title: String,
    val commitSha: String,
    val status: ForgejoActionStatus,
    val htmlUrl: String,
    val startedAtMillis: Long,
    val durationMillis: Long,
    val jobs: List<ForgejoTask>,
)

/** A Forgejo Actions task (a job within a run). [attempts] > 1 means the job was re-run. */
data class ForgejoTask(
    val id: Long,
    val name: String,
    val status: ForgejoActionStatus,
    val runUrl: String,
    val startedAtMillis: Long,
    val durationMillis: Long,
    val attempts: Int = 1,
)

/**
 * A raw Forgejo Actions task as returned by `/actions/tasks` (one job of a run). Pages of these are
 * accumulated and grouped into [ForgejoRun]s by run number — see [groupRuns] — so that a run whose
 * jobs straddle a page boundary is still assembled correctly.
 */
data class ForgejoTaskRaw(
    val id: Long,
    val name: String?,
    val status: String?,
    val runNumber: Long,
    val url: String?,
    val workflowId: String?,
    val headBranch: String?,
    val headSha: String?,
    val displayTitle: String?,
    val createdAt: String?,
    val updatedAt: String?,
    val runStartedAt: String?,
)
