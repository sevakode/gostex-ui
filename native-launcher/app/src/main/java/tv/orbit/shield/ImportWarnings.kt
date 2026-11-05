package tv.orbit.shield

/** Bound the notice for large libraries; inaccessible roots take priority over
 * individual bad files. The omitted count remains exact. */
internal class ImportWarnings(private val limit: Int = 5, private val maxCodePoints: Int = 240) {
    init { require(limit > 0 && maxCodePoints > 0) }
    private val roots = mutableListOf<String>()
    private val files = mutableListOf<String>()
    private var total = 0

    fun add(rootFailure: Boolean = false, message: () -> String) {
        total++
        val bucket = if (rootFailure) roots else files
        if (bucket.size >= limit) return
        val text = message()
        val length = text.codePointCount(0, text.length)
        bucket += if (length <= maxCodePoints) text else text.substring(0, text.offsetByCodePoints(0, maxCodePoints)) + "…"
    }

    fun snapshot(): Pair<List<String>, Int> {
        val visible = (roots + files).take(limit)
        return visible to total - visible.size
    }
}
