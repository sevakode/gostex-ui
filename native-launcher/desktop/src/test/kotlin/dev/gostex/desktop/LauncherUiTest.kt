package dev.gostex.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.gostex.core.ContentItem
import dev.gostex.core.ContentKind
import dev.gostex.core.LaunchTarget
import dev.gostex.core.ReturnAnchor
import dev.gostex.desktop.platform.ArgvFixture
import dev.gostex.desktop.platform.DesktopSettings
import dev.gostex.desktop.platform.DesktopSettingsStore
import dev.gostex.desktop.platform.DesktopUpdates
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.DataInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Rendered Compose tests: no native OS input, window activation, or Deck claims. */
@OptIn(ExperimentalTestApi::class)
class LauncherUiTest {
    @Test fun `diagnostic choice persists and Back returns through the update panel`() {
        withLauncher(DesktopSettings(showDemo = false, items = cards, selectedId = cards[0].id), withUpdates = true) { model, _ ->
            onRoot().performKeyInput { pressKey(Key.F1) }
            onNodeWithText("Updates and diagnostics").performScrollTo().performClick()
            onNodeWithText("Check now").assertIsFocused()
            screenshot("desktop-updates.png")
            onNodeWithText("✓  Development debug mode").performScrollTo().performClick()
            waitUntil(timeoutMillis = 5_000) { !model.settings.debugEnabled }
            assertFalse(model.store.load().debugEnabled)
            onRoot().performKeyInput { pressKey(Key.Escape) }
            onNodeWithText("Updates and diagnostics").assertIsFocused()
            onRoot().performKeyInput { pressKey(Key.Escape) }
            card(cards[0]).assertIsFocused()
        }
    }
    private val cards = List(10) { index ->
        ContentItem("app:test:$index", "Test app ${index + 1}", "Description ${index + 1}",
            ContentKind.APP, LaunchTarget.Process(javaExecutable()))
    }

    @Test
    fun `initial focus never rewrites invalid or future settings`() {
        listOf("schema.version=1\nlanguage=unknown\nitems.count=0\n", "schema.version=99\nprivate.custom=preserved\n").forEach { raw ->
            withLauncher(DesktopSettings(), rawConfig = raw) { model, directory ->
                runOnIdle { assertTrue(model.store.warnings.isNotEmpty()) }
                mainClock.advanceTimeBy(1000)
                waitForIdle()
                assertEquals(raw, Files.readString(directory.resolve("desktop.properties")))
            }
        }
    }

    @Test
    fun `normal close flushes the current selection before exiting`() {
        withLauncher(DesktopSettings(showDemo = false, items = cards, selectedId = cards[0].id)) { model, _ ->
            var closed = false
            runOnIdle { model.selectedId = cards[7].id; model.close { closed = true } }
            waitUntil(timeoutMillis = 5_000) { closed }
            assertEquals(cards[7].id, model.store.load().selectedId)
        }
    }

    @Test
    fun `saved offscreen card receives focus and keyboard navigation survives drawer`() {
        withLauncher(DesktopSettings(language = "en", showDemo = false,
            selectedId = cards[8].id, items = cards)) { model, _ ->
            card(cards[8]).assertIsDisplayed().assertIsFocused()
            screenshot("desktop-library.png")

            onRoot().performKeyInput { pressKey(Key.DirectionRight) }
            card(cards[9]).assertIsDisplayed().assertIsFocused()
            waitUntil(timeoutMillis = 5_000) { model.settings.selectedId == cards[9].id }
            assertEquals(cards[9].id, model.store.load().selectedId)

            onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
            card(cards[8]).assertIsDisplayed().assertIsFocused()
            onRoot().performKeyInput { pressKey(Key.F1) }
            onNodeWithText("Close").assertIsDisplayed().assertIsFocused()
            runOnIdle { assertTrue(model.drawer) }
            screenshot("desktop-settings.png")
            onRoot().performKeyInput { pressKey(Key.Escape) }
            runOnIdle { assertFalse(model.drawer) }
            card(cards[8]).assertIsDisplayed().assertIsFocused()
        }
    }

    @Test
    fun `return anchor follows identity after reorder and nearby card after removal`() {
        withLauncher(DesktopSettings(language = "en", showDemo = false,
            selectedId = cards[8].id, items = cards)) { model, _ ->
            card(cards[8]).assertIsFocused()
            val reordered = cards.reversed()
            runOnIdle {
                model.returnAnchor = ReturnAnchor("desktop:library", cards[8].id, 8)
                model.save { it.copy(items = reordered) }
            }
            waitUntil(timeoutMillis = 5_000) { model.settings.items == reordered }
            card(cards[8]).assertIsDisplayed().assertIsFocused()

            val remaining = reordered.filterNot { it.id == cards[8].id }
            runOnIdle {
                model.returnAnchor = ReturnAnchor("desktop:library", cards[8].id, 1)
                model.save { it.copy(items = remaining) }
            }
            waitUntil(timeoutMillis = 5_000) { model.settings.items == remaining }
            card(remaining[1]).assertIsDisplayed().assertIsFocused()
            runOnIdle { assertEquals(remaining[1].id, model.selectedId) }
        }
    }

    @Test
    fun `hiding selected demo restores an available card`() {
        withLauncher(DesktopSettings(language = "en", showDemo = true,
            selectedId = "demo:second", items = cards.take(2))) { model, _ ->
            onNode(hasText("Second return test") and hasClickAction()).assertIsFocused()
            screenshot("desktop-brand-preview.png")
            onRoot().performKeyInput { pressKey(Key.F1) }
            onNodeWithText("✓  Show test apps").performClick()
            waitUntil(timeoutMillis = 5_000) { !model.settings.showDemo }
            onRoot().performKeyInput { pressKey(Key.Escape) }
            card(cards[0]).assertIsDisplayed().assertIsFocused()
            onNode(hasText("Second return test") and hasClickAction()).assertDoesNotExist()
        }
    }

    @Test
    fun `Enter launches a real child JVM and exit restores focused card`() {
        val directory = Files.createTempDirectory("gostex-ui-child-")
        try {
            val result = directory.resolve("argv.bin")
            val classpath = listOf(ArgvFixture::class.java, Unit::class.java)
                .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }
                .distinct().joinToString(File.pathSeparator)
            val launched = cards[8].copy(target = LaunchTarget.Process(javaExecutable(),
                listOf("-cp", classpath, ArgvFixture::class.java.name, "argv", result.toString(), "UI return test")))
            val items = cards.toMutableList().apply { set(8, launched) }
            withLauncher(DesktopSettings(language = "en", showDemo = false,
                selectedId = launched.id, items = items), realLaunch = true) { model, _ ->
                card(launched).assertIsFocused()
                onRoot().performKeyInput { pressKey(Key.Enter) }
                waitUntil(timeoutMillis = 15_000) { model.restore > 0 && !model.busy }
                runOnIdle {
                    assertEquals("returned", model.notice)
                    assertEquals(launched.id, model.selectedId)
                }
                card(launched).assertIsDisplayed().assertIsFocused()
                assertTrue(Files.isRegularFile(result), "The child JVM must actually run")
                DataInputStream(Files.newInputStream(result)).use {
                    it.readUTF() // Child working directory.
                    assertEquals(1, it.readInt())
                    assertEquals("UI return test", it.readUTF())
                }
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun withLauncher(
        initial: DesktopSettings,
        realLaunch: Boolean = false,
        rawConfig: String? = null,
        withUpdates: Boolean = false,
        block: ComposeUiTest.(LauncherState, Path) -> Unit
    ) {
        val directory = Files.createTempDirectory("gostex-ui-settings-")
        try {
            val store = DesktopSettingsStore(directory.resolve("desktop.properties"))
            store.save(initial)
            if (rawConfig != null) Files.writeString(store.path, rawConfig)
            val loaded = store.load()
            runComposeUiTest {
                lateinit var model: LauncherState
                setContent {
                    val scope = rememberCoroutineScope()
                    model = remember { LauncherState(store, loaded, scope, if (withUpdates) DesktopUpdates(directory.resolve("runtime")) else null) }
                    val strings = remember { Strings("en") }
                    MaterialTheme(colorScheme = darkColorScheme()) {
                        Launcher(model, strings, active = true,
                            onLaunch = { item, items ->
                                if (realLaunch) model.launch(item, items) {}
                                else error("Unexpected process launch")
                            },
                            chooseFile = { error("Native file dialog is outside this test") },
                            onQuit = { error("Unexpected quit") })
                    }
                }
                waitForIdle()
                block(model, directory)
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun ComposeUiTest.card(item: ContentItem): SemanticsNodeInteraction =
        onNode(hasText(item.title) and hasClickAction())

    private fun ComposeUiTest.screenshot(name: String) {
        val workspace = generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("AGENTS.md")) }
        val destination = workspace.resolve(".reports/universal").resolve(name)
        Files.createDirectories(destination.parent)
        Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).use { image ->
            requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { data ->
                Files.write(destination, data.bytes)
            }
        }
    }

    private fun javaExecutable(): String = Path.of(System.getProperty("java.home"), "bin", "java").toString()
}
