package octris.forgejo.settings

import com.intellij.collaboration.auth.ServerAccount

/**
 * A configured Forgejo account in the collaboration auth framework. The token lives in the password
 * safe (managed by [ForgejoAccountManager]); identity is the stable `server|login` id so re-adding
 * the same login on the same server updates rather than duplicates ([ServerAccount.equals] is by id).
 */
class ForgejoAccount(
    override val name: String,
    override val server: ForgejoServerPath,
    override val id: String = "$server|$name",
) : ServerAccount()
