package octris.forgejo.settings

import com.intellij.collaboration.auth.AccountManager
import com.intellij.collaboration.auth.PersistentDefaultAccountHolder
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope

/** Per-project default Forgejo account (collaboration auth framework). */
@Service(Service.Level.PROJECT)
@State(name = "ForgejoDefaultAccount", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class ForgejoDefaultAccountHolder(project: Project, scope: CoroutineScope) :
    PersistentDefaultAccountHolder<ForgejoAccount>(project, scope) {

    override fun accountManager(): AccountManager<ForgejoAccount, *> = service<ForgejoAccountManager>()

    override fun notifyDefaultAccountMissing() = Unit
}
