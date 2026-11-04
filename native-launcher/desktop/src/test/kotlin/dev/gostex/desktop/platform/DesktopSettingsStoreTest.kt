package dev.gostex.desktop.platform

import dev.gostex.core.ContentItem
import dev.gostex.core.ContentKind
import dev.gostex.core.LaunchTarget
import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopSettingsStoreTest {
    @Test fun `update debug and gaming choices persist without changing the library`() {
        val folder = Files.createTempDirectory("gostex-update-settings-")
        try {
            val store = DesktopSettingsStore(folder.resolve("desktop.properties"))
            val settings = DesktopSettings(language = "ru", items = listOf(item("app:kept", "Kept", emptyList())),
                feedUrl = "https://github.com/sevakode/gostex/releases/latest/download/linux-x64.properties",
                updatesEnabled = false, autoDownload = false, debugEnabled = false, updateIntervalHours = 24, gamingFullscreen = false)
            store.save(settings)
            assertEquals(settings, store.load())
            assertFailsWith<IllegalArgumentException> { store.save(settings.copy(feedUrl = "http://example.test/feed")) }
            assertFailsWith<IllegalArgumentException> { store.save(settings.copy(feedUrl = "https://user:secret@example.test/feed")) }
            assertEquals(settings, store.load())
        } finally { folder.toFile().deleteRecursively() }
    }
    @Test
    fun `settings keep unicode argv empty strings metadata selection and item order`() {
        val directory = Files.createTempDirectory("gostex-settings-")
        try {
            val path = directory.resolve("nested/desktop.properties")
            val store = DesktopSettingsStore(path)
            val first = item("app:uno", "Игра ñ", listOf("", "two words", "a\nb", "\\", "${'$'}(command)", "\"quoted\""))
            val second = item("game:dos", "第二", listOf("--rom", "/home/user/ROM с пробелом.rom"))
                .copy(kind = ContentKind.GAME)
            val settings = DesktopSettings("ru", fullscreen = true, showDemo = false,
                selectedId = second.id, items = listOf(second, first))
            store.save(settings)
            assertEquals(settings, DesktopSettingsStore(path).load())
            assertTrue(store.warnings.isEmpty())
            assertEquals(listOf(path.fileName.toString()), Files.list(path.parent).use { files ->
                files.map { it.fileName.toString() }.toList()
            })
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `missing file returns defaults without creating config`() {
        val directory = Files.createTempDirectory("gostex-missing-settings-")
        try {
            val path = directory.resolve("desktop.properties")
            val store = DesktopSettingsStore(path)
            assertEquals(DesktopSettings(), store.load())
            assertTrue(store.warnings.isEmpty())
            assertFalse(Files.exists(path))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `invalid old fields use defaults and invalid item does not shift argument positions`() {
        val directory = Files.createTempDirectory("gostex-old-settings-")
        try {
            val path = directory.resolve("desktop.properties")
            val store = DesktopSettingsStore(path)
            store.save(DesktopSettings(items = listOf(item("app:one", "One", listOf("first", "second")))))
            val properties = Properties().apply { Files.newBufferedReader(path, UTF_8).use(::load) }
            properties.setProperty("language", "xx")
            properties.setProperty("fullscreen", "sure")
            properties.setProperty("showDemo", "-1")
            properties.setProperty("selectedId", "")
            properties.remove("item.0.target.argument.0")
            Files.newBufferedWriter(path, UTF_8).use { properties.store(it, null) }
            val bytes = Files.readAllBytes(path)
            assertEquals(DesktopSettings(), store.load())
            assertEquals(listOf("config.invalid-values"), store.warnings)
            assertContentEquals(bytes, Files.readAllBytes(path))
            store.save(DesktopSettings(language = "es"))
            assertEquals("es", store.load().language)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `future and malformed config stay untouched on load and save`() {
        val directory = Files.createTempDirectory("gostex-unknown-settings-")
        try {
            val path = directory.resolve("desktop.properties")
            for (source in listOf("schema.version=99\nprivate.future.field=preserve\n", "schema.version=1\nbad=\\uZZZZ\n", "language=old\n")) {
                val bytes = source.toByteArray(UTF_8)
                Files.write(path, bytes)
                val store = DesktopSettingsStore(path)
                assertEquals(DesktopSettings(), store.load())
                assertTrue(store.warnings.isNotEmpty())
                assertContentEquals(bytes, Files.readAllBytes(path))
                assertFailsWith<IOException> { store.save(DesktopSettings(language = "en")) }
                assertContentEquals(bytes, Files.readAllBytes(path))
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `save rechecks schema after another version replaced loaded file`() {
        val directory = Files.createTempDirectory("gostex-replaced-settings-")
        try {
            val path = directory.resolve("desktop.properties")
            val store = DesktopSettingsStore(path)
            store.save(DesktopSettings())
            store.load()
            val bytes = "schema.version=2\nitems.new-layout=preserve\n".toByteArray(UTF_8)
            Files.write(path, bytes)
            assertFailsWith<IOException> { store.save(DesktopSettings(fullscreen = true)) }
            assertContentEquals(bytes, Files.readAllBytes(path))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun item(id: String, title: String, arguments: List<String>): ContentItem = ContentItem(
        id, title, "Описание\nDescription = test", ContentKind.APP,
        LaunchTarget.Process("/usr/bin/java", arguments, "/tmp")
    )
}
