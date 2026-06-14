package octris.forgejo.settings

import com.intellij.collaboration.auth.ui.AccountsPanelFactory
import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import octris.forgejo.ForgejoBundle

/**
 * Settings page under **Version Control | Forgejo Integration** that manages Forgejo accounts
 * (add/remove/set default) using the collaboration auth framework's [AccountsPanelFactory] — the
 * same panel the GitHub/GitLab plugins use.
 */
class ForgejoSettingsConfigurable(private val project: Project) :
    BoundConfigurable(ForgejoBundle.message("settings.displayName")) {

    private var uiScope: CoroutineScope? = null

    override fun createPanel(): DialogPanel {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { uiScope = it }
        val accountManager = service<ForgejoAccountManager>()
        val defaultAccountHolder = project.service<ForgejoDefaultAccountHolder>()
        val accountsModel = ForgejoAccountsListModel()
        val detailsProvider = ForgejoAccountsDetailsProvider(scope, accountsModel)
        val actionsController = ForgejoAccountsPanelActionsController(project, accountsModel)
        val panelFactory = AccountsPanelFactory(scope, accountManager, defaultAccountHolder, accountsModel)

        return panel {
            row {
                panelFactory.accountsPanelCell(this, detailsProvider, actionsController).align(Align.FILL)
            }.resizableRow()
        }
    }

    override fun disposeUIResources() {
        uiScope?.cancel()
        uiScope = null
        super.disposeUIResources()
    }
}
