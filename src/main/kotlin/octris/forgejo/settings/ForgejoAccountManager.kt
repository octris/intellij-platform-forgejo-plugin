package octris.forgejo.settings

import com.intellij.collaboration.auth.AccountManagerBase
import com.intellij.collaboration.auth.AccountsRepository
import com.intellij.collaboration.auth.CredentialsRepository
import com.intellij.collaboration.auth.PasswordSafeCredentialsRepository
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger

/**
 * Application-level account manager built on the collaboration auth framework. The base class
 * handles the accounts `StateFlow`, suspend CRUD, and credential coordination; we just supply the
 * account store ([ForgejoAccountsRepository]) and a password-safe credentials repository (token = a
 * plain string, so the mapper is identity).
 */
@Service(Service.Level.APP)
class ForgejoAccountManager : AccountManagerBase<ForgejoAccount, String>(LOG) {

    private val credentials = PasswordSafeCredentialsRepository<ForgejoAccount, String>(
        SERVICE_NAME,
        object : PasswordSafeCredentialsRepository.CredentialsMapper<String> {
            override fun serialize(credentials: String): String = credentials
            override fun deserialize(credentials: String): String = credentials
        },
    )

    override fun accountsRepository(): AccountsRepository<ForgejoAccount> = service<ForgejoAccountsRepository>()

    override fun credentialsRepository(): CredentialsRepository<ForgejoAccount, String> = credentials

    companion object {
        private const val SERVICE_NAME = "Forgejo Integration"
        private val LOG = logger<ForgejoAccountManager>()
    }
}
