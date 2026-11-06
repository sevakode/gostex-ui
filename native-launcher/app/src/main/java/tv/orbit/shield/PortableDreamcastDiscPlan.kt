package tv.orbit.shield

import java.util.Locale

/** Validates the named track files, never discovers them from naming conventions. */
internal object PortableDreamcastDiscPlanner {
    private val gdiTrack = Regex("""^(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(?:"([^"]+)"|(\S+))\s+(\d+)\s*$""")

    fun build(files: List<DiscImport.File>, selectedPaths: List<String>, readText: (DiscImport.File) -> String,
              checkpoint: () -> Unit = {}): PortableDiscPlan {
        require(files.size <= 10000 && selectedPaths.size <= 8192 && selectedPaths.distinct().size == selectedPaths.size)
        val index = files.groupBy { it.path }
        val byId = mutableMapOf<String, String>()
        val selected = linkedMapOf<String, List<DiscImport.File>>()
        val all = linkedMapOf<String, DiscImport.File>()
        fun include(file: DiscImport.File) {
            require(file.id.isNotBlank() && PortablePath.fromComponents(file.path.split('/')) != null)
            val old = byId.putIfAbsent(file.id, file.path)
            require(old == null || old == file.path) { "Ambiguous document identity" }
            all.putIfAbsent(file.path, file)
        }
        for (path in selectedPaths) {
            checkpoint()
            val descriptor = index[path]?.singleOrNull() ?: error("Missing or ambiguous descriptor")
            val text = readText(descriptor)
            val references = when (descriptor.extension) {
                "gdi" -> gdiReferences(text)
                "cue" -> DiscImport.cueReferences(text)
                else -> error("Unsupported Dreamcast descriptor")
            }
            val tracks = references.map { reference ->
                checkpoint()
                val trackPath = DiscImport.resolveRelative(path, reference)
                val track = index[trackPath]?.singleOrNull() ?: error("Missing or ambiguous track")
                require(track.id != descriptor.id && track.extension.lowercase(Locale.ROOT) !in setOf("gdi", "cue", "m3u", "chd", "cdi"))
                track
            }.distinctBy { it.path }
            require(tracks.isNotEmpty())
            val group = listOf(descriptor) + tracks
            group.forEach(::include)
            selected[path] = group
        }
        return PortableDiscPlan(selected, all.values.toList())
    }

    /** Conservative subset of Flycast's GDI reader; reject ambiguous or unsafe inputs. */
    internal fun gdiReferences(text: String): List<String> {
        require(text.toByteArray(Charsets.UTF_8).size < 16384 && '\u0000' !in text && '\uFFFD' !in text && !text.startsWith('\uFEFF'))
        val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val count = lines.firstOrNull()?.toIntOrNull() ?: error("Invalid track count")
        require(count in 3..99 && lines.size == count + 1)
        var lastFad = -1L
        return lines.drop(1).mapIndexed { index, line ->
            val match = gdiTrack.matchEntire(line) ?: error("Invalid track")
            val (number, fad, control, sector) = (1..4).map { match.groupValues[it].toLongOrNull() ?: error("Invalid track number") }
            val offset = match.groupValues[7].toLongOrNull() ?: error("Invalid offset")
            require(number == index + 1L && fad in 0..Int.MAX_VALUE.toLong() && fad > lastFad && offset in 0..Int.MAX_VALUE.toLong())
            require(control in setOf(0L, 4L) && sector in setOf(2048L, 2352L))
            require((index != 0 || control == 4L) && (index != 1 || control == 0L) &&
                (index != 2 || (control == 4L && fad == 45000L)))
            lastFad = fad
            match.groupValues[5].ifEmpty { match.groupValues[6] }
        }
    }
}
