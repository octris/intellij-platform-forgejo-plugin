package octris.forgejo.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * Application-level, persisted Forgejo connection settings.
 *
 * Only non-secret values live here; the access token is stored separately in the
 * IDE password safe via [ForgejoCredentials].
 *
 * Note: this is global (one Forgejo instance for the whole IDE). If you later need
 * per-project servers, move this to [Service.Level.PROJECT] and key the credentials
 * by project/remote.
 */
@Service(Service.Level.APP)
@State(name = "ForgejoSettings", storages = [Storage("forgejo.xml")])
class ForgejoSettings : SimplePersistentStateComponent<ForgejoSettings.State>(State()) {

    class State : BaseState() {
        var server by string("")
    }

    /** Base URL of the Forgejo instance, e.g. `https://git.octr.is`. */
    var server: String
        get() = state.server.orEmpty()
        set(value) {
            state.server = value.trim().removeSuffix("/")
        }

    companion object {
        val instance: ForgejoSettings get() = service()
    }
}
