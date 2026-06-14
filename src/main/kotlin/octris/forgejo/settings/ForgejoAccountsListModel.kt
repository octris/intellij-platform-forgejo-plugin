package octris.forgejo.settings

import com.intellij.collaboration.auth.ui.AccountsListModel
import com.intellij.collaboration.auth.ui.MutableAccountsListModel

/** Editable accounts list model with a selectable default, for the settings panel. */
class ForgejoAccountsListModel :
    MutableAccountsListModel<ForgejoAccount, String>(),
    AccountsListModel.WithDefault<ForgejoAccount, String> {

    override var defaultAccount: ForgejoAccount? = null
}
