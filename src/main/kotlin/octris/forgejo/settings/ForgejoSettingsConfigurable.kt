package octris.forgejo.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import octris.forgejo.ForgejoBundle

/**
 * Settings page under <kbd>Settings | Tools | Forgejo Integration</kbd> for configuring
 * the Forgejo server URL and personal access token.
 */
class ForgejoSettingsConfigurable : BoundConfigurable(ForgejoBundle.message("settings.displayName")) {

    // Backing value for the password field; loaded from / flushed to the password safe
    // in reset()/apply() so the secret is never bound directly to the UI lifecycle.
    private var token: String = ""

    override fun createPanel(): DialogPanel = panel {
        row(ForgejoBundle.message("settings.server.label")) {
            textField()
                .bindText(ForgejoSettings.instance::server)
                .columns(36)
                .comment(ForgejoBundle.message("settings.server.comment"))
        }
        row(ForgejoBundle.message("settings.token.label")) {
            passwordField()
                .bindText(::token)
                .columns(36)
                .comment(ForgejoBundle.message("settings.token.comment"))
        }
    }

    override fun reset() {
        token = ForgejoCredentials.getToken().orEmpty()
        super.reset()
    }

    override fun apply() {
        super.apply()
        ForgejoCredentials.setToken(token)
    }
}
