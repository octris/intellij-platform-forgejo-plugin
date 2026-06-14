package octris.forgejo.api

import com.intellij.icons.AllIcons
import javax.swing.Icon

/**
 * Forgejo combined commit-status states, mapped to an icon and label for the VCS log.
 *
 * Mirrors the `state` field of Forgejo's combined commit status / CI result.
 */
enum class ForgejoCommitStatus(val displayName: String, val icon: Icon?) {
    SUCCESS("Succeeded", AllIcons.RunConfigurations.TestPassed),
    FAILURE("Failed", AllIcons.RunConfigurations.TestFailed),
    ERROR("Errored", AllIcons.General.Error),
    PENDING("Pending", AllIcons.RunConfigurations.TestNotRan),
    RUNNING("Running", AllIcons.Actions.Execute),

    /** No status available (not configured, not loaded yet, or no CI for the commit). */
    UNKNOWN("", null);

    companion object {
        /** Maps a Forgejo combined-status `state` string to this enum. */
        fun fromState(state: String?): ForgejoCommitStatus = when (state?.lowercase()) {
            "success" -> SUCCESS
            "failure" -> FAILURE
            "error" -> ERROR
            "pending" -> PENDING
            "running" -> RUNNING
            else -> UNKNOWN
        }
    }
}
