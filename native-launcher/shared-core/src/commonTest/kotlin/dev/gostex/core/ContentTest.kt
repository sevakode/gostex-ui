package dev.gostex.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ContentTest {
    @Test
    fun existingCardKeyNamespacesRemainStable() {
        assertEquals("game:Castlevania-root-01", gameContentKey("Castlevania-root-01"))
        assertEquals("app:org.videolan.vlc", appContentKey("org.videolan.vlc"))
        assertNotEquals(gameContentKey("same"), appContentKey("same"))
    }

    @Test
    fun opaqueIdsAreNotTranslatedOrNormalized() {
        assertEquals("game:игра + World:01", gameContentKey("игра + World:01"))
        assertNotEquals(gameContentKey("World"), gameContentKey("world"))
    }

    @Test
    fun processArgumentsKeepTheirExactBoundaries() {
        val arguments = listOf("--title", "Two words", "", "a;b", "$(literal)", "quote\"value")
        val target = LaunchTarget.Process("/apps/my player", arguments, "/games/my folder")
        assertEquals("/apps/my player", target.executable)
        assertEquals(arguments, target.arguments)
        assertEquals("/games/my folder", target.workingDirectory)
    }

    @Test
    fun derivingAnActionLeavesTheOriginalActionAndCardUnchanged() {
        val originalTarget = LaunchTarget.Process("player", listOf("first.rom"))
        val original = ContentItem("game:first", "First", "Description", ContentKind.GAME, originalTarget)
        val anotherTarget = originalTarget.copy(arguments = listOf("second.rom"))
        val another = original.copy(id = "game:second", target = anotherTarget)

        assertEquals(listOf("first.rom"), originalTarget.arguments)
        assertEquals(originalTarget, original.target)
        assertEquals("game:first", original.id)
        assertEquals(listOf("second.rom"), (another.target as LaunchTarget.Process).arguments)
    }
}
