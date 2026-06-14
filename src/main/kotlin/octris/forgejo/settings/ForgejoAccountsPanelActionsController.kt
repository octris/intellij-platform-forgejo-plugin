package octris.forgejo.settings

import com.intellij.collaboration.auth.ui.AccountsPanelActionsController
import com.intellij.openapi.project.Project
import com.intellij.ui.awt.RelativePoint
import javax.swing.JComponent

/**
 * Drives the +/edit actions of the accounts panel: opens [ForgejoAddAccountDialog] (which validates
 * the token and resolves the login) and stages the result into the list model. The panel persists
 * staged changes through the account manager on Apply.
 */
class ForgejoAccountsPanelActionsController(
    private val project: Project,
    private val model: ForgejoAccountsListModel,
) : AccountsPanelActionsController<ForgejoAccount> {

    override val isAddActionWithPopup: Boolean = false

    override fun addAccount(parentComponent: JComponent, point: RelativePoint?) {
        val dialog = ForgejoAddAccountDialog()
        if (dialog.showAndGet()) {
            val account = ForgejoAccount(dialog.resultUsername, ForgejoServerPath(dialog.resultServer))
            model.add(account, dialog.resultToken)
        }
    }

    override fun editAccount(parentComponent: JComponent, account: ForgejoAccount) {
        val dialog = ForgejoAddAccountDialog()
        if (dialog.showAndGet()) {
            model.update(account, dialog.resultToken)
        }
    }
}
