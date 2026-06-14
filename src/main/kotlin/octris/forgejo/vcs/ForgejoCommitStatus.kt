package octris.forgejo.vcs

import com.intellij.vcs.log.data.VcsCommitExternalStatus
import octris.forgejo.api.ForgejoCommitState

/**
 * External commit status for the VCS log, in the form the platform's
 * `VcsCommitExternalStatusProvider` framework expects.
 */
sealed interface ForgejoCommitStatus : VcsCommitExternalStatus {
    /** Not fetched yet (the stub value shown while loading). */
    object NotLoaded : ForgejoCommitStatus

    /** A resolved Forgejo CI state for the commit. */
    data class Loaded(val state: ForgejoCommitState) : ForgejoCommitStatus
}
