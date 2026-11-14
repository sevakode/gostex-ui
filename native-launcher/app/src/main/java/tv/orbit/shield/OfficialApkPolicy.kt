package tv.orbit.shield

import org.json.JSONObject
import java.net.URI
import java.util.Locale

/** Release metadata is untrusted. The player pin is independent of GitHub's latest release. */
internal object OfficialApkPolicy {
    const val VLC_PACKAGE = "tv.gostex.vlc"
    const val MAX_BYTES = 160L * 1024 * 1024
    data class Pin(val tag: String, val name: String, val bytes: Long, val sha256: String,
                   val certificateSha256: String, val versionCode: Long)
    data class Source(val repo: String, val asset: Regex, val pin: Pin? = null)
    data class Artifact(val url: String, val bytes: Long, val sha256: String)
    enum class InstalledState { MISSING, CURRENT, UPDATE_AVAILABLE, UNTRUSTED }
    private val hosts = setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
    private val tagPattern = Regex("[A-Za-z0-9._-]{1,80}")
    private val shaPattern = Regex("sha256:[a-fA-F0-9]{64}")
    val sources = mapOf(
        "com.brouken.player" to Source("moneytoo/Player", Regex("Just\\.Player\\.v[0-9]+(?:\\.[0-9]+)+\\.apk")),
        "me.magnum.melonds" to Source("rafaelvcaetano/melonDS-android", Regex("app-gitHub-prod-release\\.apk")),
        "com.flycast.emulator" to Source("flyinghead/flycast", Regex("flycast-[0-9][A-Za-z0-9._-]*\\.apk")),
        "org.azahar_emu.azahar" to Source("azahar-emu/azahar", Regex("azahar-android-vanilla-[A-Za-z0-9._-]+\\.apk")),
        VLC_PACKAGE to Source("sevakode/gostex-ui", Regex("VLC-GOSTEX-3\\.7\\.2-android\\.apk"), Pin(
            tag = "vlc-gostex-3.7.2-1", name = "VLC-GOSTEX-3.7.2-android.apk", bytes = 122074150,
            sha256 = "181a4a2b38afa7ef72a750ccb631fa277344456b6979b9cebb3b9dcd64fc54f9",
            certificateSha256 = "9340af47ef933a86522ef340fd33045a874deafe6ddbd02049d3d42a2f6e74c8", versionCode = 13070200
        ))
    )

    fun allowedUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.host?.lowercase(Locale.ROOT) in hosts && uri.rawUserInfo == null &&
            (uri.port == -1 || uri.port == 443) && uri.rawFragment == null
    }.getOrDefault(false)

    fun releaseUrl(pkg: String): String {
        val source = sources[pkg] ?: error("Unknown source")
        val release = source.pin?.let { "tags/${it.tag}" } ?: "latest"
        return "https://api.github.com/repos/${source.repo}/releases/$release"
    }

    fun artifact(pkg: String, json: String): Artifact {
        val source = sources[pkg] ?: error("Unknown source")
        val release = JSONObject(json)
        require(!release.optBoolean("draft") && (source.pin != null || !release.optBoolean("prerelease")))
        val tag = release.getString("tag_name")
        require(tagPattern.matches(tag) && (source.pin == null || source.pin.tag == tag))
        val assets = release.getJSONArray("assets")
        require(assets.length() <= 1000)
        val matches = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { source.asset.matches(it.optString("name")) }
        require(matches.size == 1)
        val asset = matches.single()
        val name = asset.getString("name")
        val size = asset.getLong("size")
        val digest = asset.getString("digest")
        require(size in 1..MAX_BYTES && shaPattern.matches(digest))
        val sha256 = digest.removePrefix("sha256:").lowercase(Locale.ROOT)
        val url = asset.getString("browser_download_url")
        require(allowedUrl(url) && url == "https://github.com/${source.repo}/releases/download/$tag/$name")
        source.pin?.let { require(name == it.name && size == it.bytes && sha256 == it.sha256) }
        return Artifact(url, size, sha256)
    }

    fun trustedSigners(pin: Pin, signers: List<String>): Boolean =
        signers.size == 1 && signers.single().lowercase(Locale.ROOT) == pin.certificateSha256

    fun installedState(pkg: String, versionCode: Long?, signers: List<String> = emptyList()): InstalledState {
        val source = sources[pkg] ?: return InstalledState.UNTRUSTED
        if (versionCode == null) return InstalledState.MISSING
        if (versionCode < 0) return InstalledState.UNTRUSTED
        val pin = source.pin ?: return InstalledState.CURRENT
        if (!trustedSigners(pin, signers)) return InstalledState.UNTRUSTED
        return if (versionCode >= pin.versionCode) InstalledState.CURRENT else InstalledState.UPDATE_AVAILABLE
    }
}
