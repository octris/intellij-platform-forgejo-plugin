package octris.forgejo.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import octris.forgejo.ForgejoBundle
import octris.forgejo.api.ForgejoApiClient
import javax.swing.JComponent

/**
 * "Add Forgejo Account" dialog: server URL + token. OK validates the token against `/user`
 * (off the EDT) and only closes once the login resolves, exposing the result to the caller.
 */
class ForgejoAddAccountDialog : DialogWrapper(true) {

    private val serverField = JBTextField("https://")
    private val tokenField = JBPasswordField()
    private val statusLabel = JBLabel()

    var resultServer: String = ""
        private set
    var resultUsername: String = ""
        private set
    var resultToken: String = ""
        private set

    init {
        title = ForgejoBundle.message("dialog.add.title")
        setOKButtonText(ForgejoBundle.message("dialog.add.button"))
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row(ForgejoBundle.message("settings.server.label")) {
            cell(serverField).columns(32).comment(ForgejoBundle.message("settings.server.comment"))
        }
        row(ForgejoBundle.message("settings.token.label")) {
            cell(tokenField).columns(32).comment(ForgejoBundle.message("settings.token.comment"))
        }
        row("") { cell(statusLabel) }
    }

    override fun getPreferredFocusedComponent(): JComponent = serverField

    override fun doOKAction() {
        val server = serverField.text.trim().removeSuffix("/")
        val token = String(tokenField.password)
        if (server.isEmpty() || token.isEmpty()) {
            showError(ForgejoBundle.message("dialog.add.missing"))
            return
        }
        statusLabel.foreground = JBColor.foreground()
        statusLabel.text = ForgejoBundle.message("settings.test.checking")
        isOKActionEnabled = false
        val modality = ModalityState.stateForComponent(rootPane)
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = ForgejoApiClient().getUser(server, token)
            ApplicationManager.getApplication().invokeLater({
                result.fold(
                    onSuccess = { user ->
                        resultServer = server
                        resultUsername = user.login
                        resultToken = token
                        close(OK_EXIT_CODE)
                    },
                    onFailure = {
                        showError(ForgejoBundle.message("settings.test.fail", it.message ?: "error"))
                        isOKActionEnabled = true
                    },
                )
            }, modality)
        }
    }

    private fun showError(message: String) {
        statusLabel.foreground = JBColor.RED
        statusLabel.text = message
    }
}
