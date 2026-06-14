package octris.forgejo

import com.intellij.DynamicBundle
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey

@NonNls
private const val BUNDLE = "messages.ForgejoBundle"

/**
 * Message bundle for the Forgejo Integration plugin's own strings.
 *
 * Kept separate from the template's sample `MyBundle` so it survives once the
 * sample scaffold is removed.
 */
object ForgejoBundle : DynamicBundle(BUNDLE) {

    @JvmStatic
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any) =
        getMessage(key, *params)
}
