package octris.forgejo.settings

import com.intellij.collaboration.auth.ui.LazyLoadingAccountsDetailsProvider
import com.intellij.collaboration.auth.ui.LazyLoadingAccountsDetailsProvider.Result
import com.intellij.openapi.components.service
import icons.CollaborationToolsIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import octris.forgejo.api.ForgejoApiClient
import java.awt.Image
import java.net.URI
import javax.imageio.ImageIO

/**
 * Loads each account's display details (login + avatar) from the Forgejo API. The token may be
 * freshly entered (still staged in the list model) or already persisted, so we check both.
 */
class ForgejoAccountsDetailsProvider(
    scope: CoroutineScope,
    private val accountsModel: ForgejoAccountsListModel,
) : LazyLoadingAccountsDetailsProvider<ForgejoAccount, ForgejoAccountDetails>(scope, CollaborationToolsIcons.Review.DefaultAvatar) {

    private val client = ForgejoApiClient()

    override suspend fun loadDetails(account: ForgejoAccount): Result<ForgejoAccountDetails> {
        val token = accountsModel.newCredentials[account] ?: service<ForgejoAccountManager>().findCredentials(account)
        if (token.isNullOrEmpty()) return Result.Error("Missing token", true)
        return withContext(Dispatchers.IO) { client.getUser(account.server.toString(), token) }.fold(
            onSuccess = { Result.Success(ForgejoAccountDetails(it.login, it.avatarUrl)) },
            onFailure = { Result.Error(it.message ?: "Failed to load account", true) },
        )
    }

    override suspend fun loadAvatar(account: ForgejoAccount, url: String): Image? =
        withContext(Dispatchers.IO) { runCatching { ImageIO.read(URI(url).toURL()) }.getOrNull() }
}
