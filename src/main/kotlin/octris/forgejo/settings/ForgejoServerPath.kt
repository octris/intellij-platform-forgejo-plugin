package octris.forgejo.settings

import com.intellij.collaboration.api.ServerPath
import java.net.URI

/** A Forgejo server URL, as the collaboration auth framework's [ServerPath]. */
class ForgejoServerPath(private val uri: String) : ServerPath {

    override fun toURI(): URI = URI(uri)

    override fun toString(): String = uri

    override fun equals(other: Any?): Boolean = other is ForgejoServerPath && other.uri == uri

    override fun hashCode(): Int = uri.hashCode()
}
