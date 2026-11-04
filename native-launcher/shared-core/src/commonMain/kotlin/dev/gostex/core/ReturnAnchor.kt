package dev.gostex.core

/** The caller supplies the current items of [shelfId], rather than a stale launch snapshot. */
data class ReturnAnchor(
    val shelfId: String,
    val itemId: String?,
    val fallbackIndex: Int = 0
) {
    /** Prefer identity after reorder; choose a nearby valid card after removal. */
    fun resolve(items: List<ContentItem>): Int {
        if (items.isEmpty()) return -1
        val matchingIndex = items.indexOfFirst { it.id == itemId }
        return if (matchingIndex >= 0) matchingIndex else fallbackIndex.coerceIn(items.indices)
    }
}
