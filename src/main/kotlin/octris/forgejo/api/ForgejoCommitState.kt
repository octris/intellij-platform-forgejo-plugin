package octris.forgejo.api

/**
 * Forgejo combined commit-status states (the `state` field of the combined status API).
 */
enum class ForgejoCommitState {
    SUCCESS,
    FAILURE,
    ERROR,
    PENDING,
    RUNNING,
    WARNING;

    companion object {
        /** Maps a Forgejo combined-status `state` string to this enum, or null if unrecognized. */
        fun fromState(state: String?): ForgejoCommitState? = when (state?.lowercase()) {
            "success" -> SUCCESS
            "failure" -> FAILURE
            "error" -> ERROR
            "pending" -> PENDING
            "running" -> RUNNING
            "warning" -> WARNING
            else -> null
        }
    }
}
