package tv.orbit.shield

/** Bound JSON size and nesting before parsing untrusted portable data. */
internal fun requirePortableJson(input: String, maxBytes: Int) {
    require(maxBytes >= 0 && input.length <= maxBytes && input.toByteArray(Charsets.UTF_8).size <= maxBytes)
    var depth = 0
    var inString = false
    var escaped = false
    for (ch in input) {
        if (inString) {
            when {
                escaped -> escaped = false
                ch == '\\' -> escaped = true
                ch == '"' -> inString = false
            }
        } else when (ch) {
            '"' -> inString = true
            '{', '[' -> { depth++; require(depth <= 16) { "JSON nesting too deep" } }
            '}', ']' -> { depth--; require(depth >= 0) { "Invalid JSON nesting" } }
        }
    }
    require(!inString && depth == 0) { "Incomplete JSON" }
}
