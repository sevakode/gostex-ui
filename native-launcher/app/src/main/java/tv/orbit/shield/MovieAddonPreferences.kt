package tv.orbit.shield

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class ConfiguredMovieAddon(val addon: MovieAddon, val enabled: Boolean)

internal object MovieAddonPreferences {
    private const val KEY = "movies.addons.v1"
    private const val MAX_ADDONS = 12
    private fun prefs(context: Context) = context.getSharedPreferences("orbit", Context.MODE_PRIVATE)

    @Synchronized fun read(context: Context): List<ConfiguredMovieAddon> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        require(raw.length <= 64 * 1024)
        val json = JSONObject(raw)
        require(json.getInt("schema") == 1)
        val array = json.getJSONArray("addons")
        require(array.length() <= MAX_ADDONS)
        val list = List(array.length()) {
            val entry = array.getJSONObject(it)
            val url = entry.getString("url")
            require(url.length <= 4096 && url.startsWith("https://") && url.endsWith("/manifest.json"))
            val name = entry.getString("name")
            require(name.isNotBlank() && name.length <= 128)
            ConfiguredMovieAddon(MovieAddon(url, name), entry.getBoolean("enabled"))
        }
        require(list.map { it.addon.manifestUrl }.distinct().size == list.size)
        return list
    }

    @Synchronized fun save(context: Context, addon: ConfiguredMovieAddon): Boolean {
        val entries = read(context)
        require(addon.addon.manifestUrl.length <= 4096 && addon.addon.manifestUrl.startsWith("https://") &&
            addon.addon.manifestUrl.endsWith("/manifest.json") && addon.addon.name.isNotBlank() && addon.addon.name.length <= 128)
        val updated = entries.filterNot { it.addon.manifestUrl == addon.addon.manifestUrl } + addon
        require(updated.size <= MAX_ADDONS)
        return write(context, updated)
    }

    @Synchronized fun remove(context: Context, manifestUrl: String): Boolean {
        val entries = read(context)
        return write(context, entries.filterNot { it.addon.manifestUrl == manifestUrl })
    }

    private fun write(context: Context, updated: List<ConfiguredMovieAddon>): Boolean {
        val items = JSONArray()
        updated.forEach { items.put(JSONObject().put("url", it.addon.manifestUrl).put("name", it.addon.name).put("enabled", it.enabled)) }
        return prefs(context).edit().putString(KEY, JSONObject().put("schema", 1).put("addons", items).toString()).commit()
    }
}
