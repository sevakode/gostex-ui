package dev.gostex.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ReturnAnchorTest {
    private fun item(id: String, title: String = id) = ContentItem(
        id, title, "", ContentKind.APP, LaunchTarget.Process("test-process")
    )

    @Test
    fun identitySurvivesReorderingAndChangedMetadata() {
        val anchor = ReturnAnchor("favorites", "app:b", 1)
        val reordered = listOf(item("app:b", "New label"), item("app:c"), item("app:a"))
        assertEquals(0, anchor.resolve(reordered))
    }

    @Test
    fun removalRestoresTheNeighborAtThePreviousPosition() {
        val anchor = ReturnAnchor("apps", "app:b", 1)
        assertEquals(1, anchor.resolve(listOf(item("app:a"), item("app:c"))))
    }

    @Test
    fun removedLastCardFallsBackToTheNewLastCard() {
        val anchor = ReturnAnchor("apps", "app:c", 2)
        assertEquals(1, anchor.resolve(listOf(item("app:a"), item("app:b"))))
    }

    @Test
    fun invalidFallbackIsBoundedAndMissingIdentityUsesIt() {
        val items = listOf(item("app:a"), item("app:b"))
        assertEquals(0, ReturnAnchor("apps", null, Int.MIN_VALUE).resolve(items))
        assertEquals(1, ReturnAnchor("apps", null, Int.MAX_VALUE).resolve(items))
        assertEquals(0, ReturnAnchor("apps", null).resolve(items))
        assertEquals(1, ReturnAnchor("apps", "missing", 1).resolve(items))
    }

    @Test
    fun anExistingIdentityWinsOverAnInvalidFallback() {
        assertEquals(1, ReturnAnchor("apps", "app:b", Int.MIN_VALUE)
            .resolve(listOf(item("app:a"), item("app:b"))))
    }

    @Test
    fun emptyShelfHasNoSelection() {
        assertEquals(-1, ReturnAnchor("apps", "app:a", 7).resolve(emptyList()))
    }

    @Test
    fun sameTitleCannotRestoreTheWrongIdentity() {
        val items = listOf(item("game:a", "Shared title"), item("app:a", "Shared title"))
        assertEquals(1, ReturnAnchor("mixed", "app:a").resolve(items))
    }

    @Test
    fun anchorsForDifferentShelvesRemainIndependent() {
        val favorites = ReturnAnchor("favorites", "app:b", 1)
        val apps = ReturnAnchor("apps", "app:b", 2)
        assertEquals(0, favorites.resolve(listOf(item("app:b"), item("app:a"))))
        assertEquals(2, apps.resolve(listOf(item("app:c"), item("app:a"), item("app:b"))))
        assertEquals("favorites", favorites.shelfId)
        assertEquals("apps", apps.shelfId)
    }
}
