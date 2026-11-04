package dev.gostex.desktop.platform

import dev.gostex.core.ContentItem
import dev.gostex.core.ContentKind
import dev.gostex.core.LaunchTarget
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Locale
import java.util.Properties

data class DesktopSettings(
    val language: String = "system",
    val fullscreen: Boolean = false,
    val showDemo: Boolean = true,
    val selectedId: String? = null,
    val items: List<ContentItem> = emptyList(),
    val feedUrl: String = "",
    val updatesEnabled: Boolean = true,
    val autoDownload: Boolean = true,
    val debugEnabled: Boolean = true,
    val updateIntervalHours: Int = 6,
    val gamingFullscreen: Boolean = true
)

fun defaultConfigPath(): Path {
    val home = Path.of(System.getProperty("user.home"))
    return if (System.getProperty("os.name").lowercase(Locale.ROOT).contains("mac")) {
        home.resolve("Library/Application Support/GOSTEX/desktop.properties")
    } else {
        val xdg = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Path.of(it) }.getOrNull() }?.takeIf { it.isAbsolute }
        (xdg ?: home.resolve(".config")).resolve("gostex/desktop.properties")
    }
}

/**
 * Desktop-only preferences. Android's orbit preferences are not read or migrated here.
 * Call load/save off the UI thread. Loading never writes, including invalid/future files.
 */
class DesktopSettingsStore(val path: Path = defaultConfigPath()) {
    @Volatile
    var warnings: List<String> = emptyList()
        private set

    @Synchronized
    fun load(): DesktopSettings {
        warnings = emptyList()
        if (!Files.exists(path)) return DesktopSettings()
        val properties = try {
            readProperties()
        } catch (_: InvalidConfigException) {
            warnings = listOf("config.malformed")
            return DesktopSettings()
        }
        if (properties.getProperty("schema.version") != SCHEMA_VERSION) {
            warnings = listOf("config.unsupported")
            return DesktopSettings()
        }
        var invalid = false
        fun text(key: String, default: String, allowed: Set<String>): String {
            val value = properties.getProperty(key) ?: return default
            return value.takeIf { it in allowed } ?: default.also { invalid = true }
        }
        fun boolean(key: String, default: Boolean): Boolean =
            text(key, default.toString(), setOf("true", "false")).toBoolean()

        val language = text("language", "system", LANGUAGES)
        val fullscreen = boolean("fullscreen", false)
        val showDemo = boolean("showDemo", true)
        val feedUrl = properties.getProperty("updates.feedUrl", "").let {
            if (validFeed(it)) it else "".also { invalid = true }
        }
        val checks = boolean("updates.checksEnabled", true)
        val autoDownload = boolean("updates.autoDownload", true)
        val debug = boolean("updates.debugEnabled", true)
        val gamingFullscreen = boolean("appearance.gamingFullscreen", true)
        val interval = properties.getProperty("updates.intervalHours", "6").toIntOrNull()
            ?.takeIf { it in setOf(1, 6, 24) } ?: 6.also { invalid = true }
        val selectedId = properties.getProperty("selectedId")?.let {
            if (validText(it, 4096, allowBlank = false)) it else null.also { invalid = true }
        }
        val count = properties.getProperty("items.count")?.toIntOrNull()
            ?.takeIf { it in 0..MAX_ITEMS }
        if (count == null) invalid = true
        val seenIds = mutableSetOf<String>()
        val items = (0 until (count ?: 0)).mapNotNull { index ->
            readItem(properties, "item.$index.")?.takeIf { seenIds.add(it.id) }
                .also { if (it == null) invalid = true }
        }
        warnings = if (invalid) listOf("config.invalid-values") else emptyList()
        return DesktopSettings(language, fullscreen, showDemo, selectedId, items, feedUrl, checks, autoDownload, debug, interval, gamingFullscreen)
    }

    @Synchronized
    fun save(settings: DesktopSettings) {
        // Recheck the current file: another app/version may have replaced it after load().
        if (Files.exists(path)) {
            val existing = try {
                readProperties()
            } catch (error: InvalidConfigException) {
                throw IOException("Refusing to overwrite malformed desktop settings", error)
            }
            if (existing.getProperty("schema.version") != SCHEMA_VERSION) {
                throw IOException("Refusing to overwrite an unsupported desktop settings version")
            }
        }
        require(settings.language in LANGUAGES) { "Unsupported language" }
        require(validFeed(settings.feedUrl) && settings.updateIntervalHours in setOf(1, 6, 24)) { "Invalid update settings" }
        require(settings.items.size <= MAX_ITEMS) { "Too many items" }
        require(settings.selectedId == null || validText(settings.selectedId, 4096, false)) {
            "Invalid selected item identity"
        }
        val properties = Properties().apply {
            setProperty("schema.version", SCHEMA_VERSION)
            setProperty("language", settings.language)
            setProperty("fullscreen", settings.fullscreen.toString())
            setProperty("showDemo", settings.showDemo.toString())
            setProperty("updates.feedUrl", settings.feedUrl)
            setProperty("updates.checksEnabled", settings.updatesEnabled.toString())
            setProperty("updates.autoDownload", settings.autoDownload.toString())
            setProperty("updates.debugEnabled", settings.debugEnabled.toString())
            setProperty("updates.intervalHours", settings.updateIntervalHours.toString())
            setProperty("appearance.gamingFullscreen", settings.gamingFullscreen.toString())
            settings.selectedId?.let { setProperty("selectedId", it) }
            setProperty("items.count", settings.items.size.toString())
        }
        val seenIds = mutableSetOf<String>()
        settings.items.forEachIndexed { index, item ->
            require(seenIds.add(item.id)) { "Duplicate content identity" }
            writeItem(properties, "item.$index.", item)
        }
        val parent = path.toAbsolutePath().parent
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, ".desktop-", ".tmp")
        try {
            Files.newBufferedWriter(temporary, UTF_8).use { properties.store(it, "GOSTEX desktop preferences") }
            require(Files.size(temporary) <= MAX_CONFIG_BYTES) { "Desktop settings are too large" }
            try {
                Files.move(temporary, path.toAbsolutePath(), ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (error: AtomicMoveNotSupportedException) {
                // Keep the last valid configuration if the filesystem cannot replace atomically.
                throw IOException("The settings filesystem does not support atomic replacement", error)
            }
            warnings = emptyList()
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun readProperties(): Properties {
        if (Files.size(path) > MAX_CONFIG_BYTES) throw InvalidConfigException()
        return try {
            Properties().also { properties -> Files.newBufferedReader(path, UTF_8).use(properties::load) }
        } catch (error: IllegalArgumentException) {
            throw InvalidConfigException(error)
        } catch (error: CharacterCodingException) {
            throw InvalidConfigException(error)
        }
    }

    private fun readItem(properties: Properties, prefix: String): ContentItem? = runCatching {
        fun field(name: String): String = requireNotNull(properties.getProperty(prefix + name))
        val id = field("id").also { require(validText(it, 4096, false)) }
        val title = field("title").also { require(validText(it, 1024, false)) }
        val description = field("description").also { require(validText(it, 16_384)) }
        val kind = ContentKind.valueOf(field("kind"))
        require(field("target.type") == "process")
        val executable = field("target.executable").also { require(validPath(it)) }
        val count = field("target.arguments.count").toInt().also { require(it in 0..MAX_ARGUMENTS) }
        val arguments = (0 until count).map { field("target.argument.$it").also { require(validText(it, 32_768)) } }
        val directory = properties.getProperty(prefix + "target.directory")?.also { require(validPath(it)) }
        ContentItem(id, title, description, kind, LaunchTarget.Process(executable, arguments, directory))
    }.getOrNull()

    private fun writeItem(properties: Properties, prefix: String, item: ContentItem) {
        require(validText(item.id, 4096, false) && validText(item.title, 1024, false)) { "Invalid content title or identity" }
        require(validText(item.description, 16_384)) { "Invalid content description" }
        val target = item.target as? LaunchTarget.Process ?: error("Unsupported desktop launch target")
        require(validPath(target.executable)) { "Invalid executable path" }
        val workingDirectory = target.workingDirectory
        require(workingDirectory == null || validPath(workingDirectory)) { "Invalid working directory" }
        require(target.arguments.size <= MAX_ARGUMENTS && target.arguments.all { validText(it, 32_768) }) {
            "Invalid process arguments"
        }
        fun field(name: String, value: String) { properties.setProperty(prefix + name, value) }
        field("id", item.id)
        field("title", item.title)
        field("description", item.description)
        field("kind", item.kind.name)
        field("target.type", "process")
        field("target.executable", target.executable)
        field("target.arguments.count", target.arguments.size.toString())
        target.arguments.forEachIndexed { index, value -> field("target.argument.$index", value) }
        target.workingDirectory?.let { field("target.directory", it) }
    }

    private class InvalidConfigException(cause: Throwable? = null) : Exception(cause)

    companion object {
        private const val SCHEMA_VERSION = "1"
        private const val MAX_ITEMS = 500
        private const val MAX_ARGUMENTS = 128
        private const val MAX_CONFIG_BYTES = 1_048_576L
        private val LANGUAGES = setOf("system", "en", "es", "ru")
        private fun validFeed(value: String): Boolean = value.isEmpty() || runCatching {
            val uri = java.net.URI(value)
            value.length <= 2048 && uri.scheme == "https" && !uri.host.isNullOrBlank() &&
                uri.userInfo == null && uri.fragment == null && uri.query == null
        }.getOrDefault(false)
        private fun validText(value: String, maxLength: Int, allowBlank: Boolean = true): Boolean =
            value.length <= maxLength && '\u0000' !in value && (allowBlank || value.isNotBlank())
        private fun validPath(value: String): Boolean = validText(value, 32_768, false) &&
            runCatching { Path.of(value).isAbsolute }.getOrDefault(false)
    }
}
