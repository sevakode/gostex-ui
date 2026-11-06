package tv.orbit.shield

/** A path relative to an explicitly selected portable root, never a URI or an absolute path.
 * Components are preserved verbatim: filename case, Unicode and literal percent signs matter.
 * The eventual SAF resolver must resolve each component under the granted tree and reject
 * missing, ambiguous or provider-escaped documents; this value alone cannot grant access. */
internal class PortablePath private constructor(val components: List<String>) {
    companion object {
        private const val MAX_COMPONENTS = 128
        private const val MAX_COMPONENT_LENGTH = 255
        private const val MAX_PATH_LENGTH = 4096

        fun fromComponents(components: List<String>): PortablePath? {
            if (components.isEmpty() || components.size > MAX_COMPONENTS) return null
            var length = 0
            for (component in components) {
                if (component.isEmpty() || component == "." || component == ".." ||
                    component.length > MAX_COMPONENT_LENGTH ||
                    component.any { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }) return null
                length += component.length + 1
                if (length > MAX_PATH_LENGTH) return null
            }
            return PortablePath(java.util.Collections.unmodifiableList(components.toList()))
        }
    }
}

/** Map an exact descendant of a selected root to a relative portable path.
 * A same-named sibling and the root itself are not descendants; no case-folding,
 * percent decoding, trimming, string-prefix comparison or parent traversal is done. */
internal fun portableRelativePath(root: List<String>, document: List<String>): PortablePath? {
    if (document.size <= root.size || document.take(root.size) != root) return null
    return PortablePath.fromComponents(document.drop(root.size))
}
