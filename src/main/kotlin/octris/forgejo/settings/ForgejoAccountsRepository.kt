package octris.forgejo.settings

import com.intellij.collaboration.auth.AccountsRepository
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.annotations.XCollection

/**
 * Persists the (non-secret) Forgejo account list for the collaboration auth framework's
 * [AccountsRepository]. Tokens are stored separately in the password safe.
 */
@Service(Service.Level.APP)
@State(name = "ForgejoAccounts", storages = [Storage("forgejo.xml")])
class ForgejoAccountsRepository : PersistentStateComponent<ForgejoAccountsRepository.State>, AccountsRepository<ForgejoAccount> {

    class State {
        @get:XCollection(style = XCollection.Style.v2)
        var accounts: MutableList<AccountEntry> = mutableListOf()
    }

    class AccountEntry {
        var id: String = ""
        var name: String = ""
        var server: String = ""
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    override var accounts: Set<ForgejoAccount>
        get() = myState.accounts.map { ForgejoAccount(it.name, ForgejoServerPath(it.server), it.id) }.toSet()
        set(value) {
            myState.accounts = value.map { account ->
                AccountEntry().apply {
                    id = account.id
                    name = account.name
                    server = account.server.toString()
                }
            }.toMutableList()
        }
}
