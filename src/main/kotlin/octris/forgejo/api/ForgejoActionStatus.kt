package octris.forgejo.api

/** Status of a Forgejo Actions run or task (job). */
enum class ForgejoActionStatus {
    SUCCESS,
    FAILURE,
    CANCELLED,
    SKIPPED,
    RUNNING,
    WAITING,
    BLOCKED,
    UNKNOWN;

    /** Still progressing — drives the spinner, live elapsed time, and auto-refresh. */
    val isInProgress: Boolean
        get() = this == RUNNING || this == WAITING || this == BLOCKED

    companion object {
        fun fromString(raw: String?): ForgejoActionStatus = when (raw?.lowercase()) {
            "success" -> SUCCESS
            "failure" -> FAILURE
            "cancelled", "canceled" -> CANCELLED
            "skipped" -> SKIPPED
            "running" -> RUNNING
            "waiting" -> WAITING
            "blocked" -> BLOCKED
            else -> UNKNOWN
        }
    }
}
