package octris.forgejo.settings

import com.intellij.collaboration.auth.AccountDetails

/** Account details (resolved login + avatar URL) shown in the accounts list. */
class ForgejoAccountDetails(
    override val name: String,
    override val avatarUrl: String?,
) : AccountDetails
