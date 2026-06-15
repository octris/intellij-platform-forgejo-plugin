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

    /**
     * A resolved Forgejo CI state for the commit, with a link to the run/job page (if any), plus the
     * commit SHA and the repo it was resolved from — so a click can open the CI tab on that run.
     */
    data class Loaded(
        val state: ForgejoCommitState,
        val url: String?,
        val commitSha: String,
        val context: ForgejoRepoContext,
    ) : ForgejoCommitStatus
}
