package tv.orbit.shield

import java.util.Locale

/** These titles need emulator-managed data whose restore is not yet supported. */
internal class PortableInstalled3dsUnsupported(val gameCount: Int) : IllegalStateException()
internal data class PortableExportItem(val platform: String, val fileNames: List<String>)

internal object PortableExportPreflight {
    fun validate(items: List<PortableExportItem>) {
        val count = items.count { item ->
            item.platform == "Nintendo 3DS" && item.fileNames.any {
                it.substringAfterLast('.', "").lowercase(Locale.ROOT) == "app"
            }
        }
        if (count > 0) throw PortableInstalled3dsUnsupported(count)
    }
}
