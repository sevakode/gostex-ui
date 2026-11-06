package tv.orbit.shield

import org.json.JSONArray
import org.json.JSONObject

/** A deliberately narrow, versioned snapshot; this codec never reads or writes preferences.
 * Add keys only after reviewing their semantics and privacy. A missing key stays missing. */
internal object PortableSettingsCodec {
    private const val MAX_BYTES = 128 * 1024
    private const val MAX_STRING = 8192
    private const val MAX_SET_ITEMS = 512
    private enum class Kind { STRING, BOOLEAN, INT, STRING_SET }
    private val kinds = buildMap {
        listOf("language", "controllerIcons", "appearance.coverStyle", "appearance.tileSize",
            "clock.format", "clock.zones", "art.captionMode", "art.speed", "art.revealStyle",
            "art.priorities", "library.ndsEmulator", "home.order", "home.shelves.order",
            "video.order", "covers.onlineSources", "grafana.mode", "grafana.scrollMode",
            "grafana.rotationMode").forEach { put(it, Kind.STRING) }
        listOf("appearance.favoriteBadgeVisible", "clock.visible", "clock.showDate", "art.interludes",
            "apps.tabVisible", "library.tabVisible", "status.networkVisible", "status.mediaPlayerVisible",
            "status.mediaPlayerShowPaused", "covers.onlineLandscape", "covers.automatic").forEach { put(it, Kind.BOOLEAN) }
        listOf("art.holdSeconds", "grafana.refreshSeconds", "grafana.rotationSeconds").forEach { put(it, Kind.INT) }
        listOf("home.shelves.visible", "video.visible").forEach { put(it, Kind.STRING_SET) }
    }

    internal fun supportedKeys(): Set<String> = kinds.keys.toSet()

    private fun validText(value: String): String {
        require(value.length <= MAX_STRING && value.none(Char::isISOControl)) { "Invalid setting text" }
        return value
    }
    private fun checked(key: String, value: Any): Any = when (kinds[key] ?: error("Unsupported setting")) {
        Kind.STRING -> validText(value as? String ?: error("Invalid setting type"))
        Kind.BOOLEAN -> value as? Boolean ?: error("Invalid setting type")
        Kind.INT -> value as? Int ?: error("Invalid setting type")
        Kind.STRING_SET -> {
            val items = value as? Set<*> ?: error("Invalid setting type")
            require(items.size <= MAX_SET_ITEMS)
            items.map { validText(it as? String ?: error("Invalid set item")) }.toSet()
        }
    }

    /** Pass SharedPreferences.all; unsupported keys are excluded without inspecting their values. */
    fun capture(all: Map<String, *>): Map<String, Any> = kinds.keys.mapNotNull { key ->
        if (!all.containsKey(key)) null else key to checked(key, all[key] ?: error("Null setting"))
    }.toMap()

    fun encode(settings: Map<String, Any>): String {
        val values = JSONObject()
        settings.toSortedMap().forEach { (key, raw) ->
            val value = checked(key, raw)
            values.put(key, if (value is Set<*>) JSONArray(value.map { it as String }.sorted()) else value)
        }
        val result = JSONObject().put("version", 1).put("values", values).toString()
        requirePortableJson(result, MAX_BYTES)
        return result
    }

    fun decode(serialized: String): Map<String, Any> {
        requirePortableJson(serialized, MAX_BYTES)
        val root = JSONObject(serialized)
        require(root.length() == 2 && root.opt("version") == 1 && root.has("values")) { "Unsupported settings document" }
        val values = root.opt("values") as? JSONObject ?: error("Invalid settings document")
        require(values.length() <= kinds.size)
        val result = mutableMapOf<String, Any>()
        val keys = values.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val kind = kinds[key] ?: error("Unsupported setting")
            val raw = values.opt(key) ?: error("Null setting")
            val value = if (kind == Kind.STRING_SET) {
                val array = raw as? JSONArray ?: error("Invalid setting type")
                require(array.length() <= MAX_SET_ITEMS)
                val list = (0 until array.length()).map { array.opt(it) as? String ?: error("Invalid set item") }
                require(list.size == list.distinct().size) { "Duplicate set item" }
                list.toSet()
            } else raw
            result[key] = checked(key, value)
        }
        return result.toMap()
    }
}
