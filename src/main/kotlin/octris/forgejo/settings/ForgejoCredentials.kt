package octris.forgejo.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

/**
 * Stores the Forgejo personal access token in the IDE password safe rather than in
 * plain-text settings.
 */
object ForgejoCredentials {

    private val attributes: CredentialAttributes
        get() = CredentialAttributes(generateServiceName("Forgejo Integration", "token"))

    fun getToken(): String? = PasswordSafe.instance.getPassword(attributes)?.takeIf { it.isNotEmpty() }

    fun setToken(token: String?) {
        PasswordSafe.instance.setPassword(attributes, token?.takeIf { it.isNotEmpty() })
    }
}
