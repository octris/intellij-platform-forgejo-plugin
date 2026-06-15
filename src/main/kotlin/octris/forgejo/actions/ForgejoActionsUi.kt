package octris.forgejo.actions

import com.intellij.collaboration.ui.icon.CIBuildStatusIcons
import com.intellij.ui.AnimatedIcon
import octris.forgejo.api.ForgejoActionStatus
import javax.swing.Icon

/** Maps a Forgejo Actions status to a CI status icon (animated spinner while running). */
internal fun ForgejoActionStatus.icon(): Icon = when (this) {
    ForgejoActionStatus.SUCCESS -> CIBuildStatusIcons.success
    ForgejoActionStatus.FAILURE -> CIBuildStatusIcons.failed
    ForgejoActionStatus.CANCELLED -> CIBuildStatusIcons.cancelled
    ForgejoActionStatus.SKIPPED -> CIBuildStatusIcons.skipped
    ForgejoActionStatus.RUNNING -> AnimatedIcon.Default.INSTANCE
    ForgejoActionStatus.WAITING, ForgejoActionStatus.BLOCKED -> CIBuildStatusIcons.pending
    ForgejoActionStatus.UNKNOWN -> CIBuildStatusIcons.pending
}

/** Formats an elapsed/duration value in milliseconds, e.g. "4s", "2m 13s", "1h 5m". */
internal fun formatElapsed(millis: Long): String {
    val seconds = millis / 1000
    return when {
        seconds >= 3600 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        seconds >= 60 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds}s"
    }
}
