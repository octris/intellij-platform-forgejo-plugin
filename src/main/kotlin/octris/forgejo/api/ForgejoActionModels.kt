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

/** A Forgejo Actions task (a job within a run). */
data class ForgejoTask(
    val id: Long,
    val name: String,
    val status: ForgejoActionStatus,
    val runUrl: String,
    val startedAtMillis: Long,
    val durationMillis: Long,
)
