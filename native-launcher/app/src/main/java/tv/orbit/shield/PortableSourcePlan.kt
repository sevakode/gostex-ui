package tv.orbit.shield

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale

internal data class PortableSourceStamp(val bytes: Long, val modified: Long)
internal interface PortablePlanningSource {
    val rootDocumentId: String
    fun locate(savedFile: String, checkActive: () -> Unit): PortableSourceFile
    fun resolve(documentId: String, checkActive: () -> Unit): PortableSourceFile
    fun files(checkActive: () -> Unit): List<DiscImport.File>
    fun stamp(documentId: String): PortableSourceStamp
    fun open(documentId: String): InputStream
}
internal interface PortablePlanningCover {
    fun stamp(): PortableSourceStamp
    fun open(): InputStream
}
internal data class PortablePreparedSourcePlan(
    val inputs: List<PortableCopyInput>,
    val variants: Map<String, PortableVariantBinding>,
    val covers: Map<String, String>,
    val protectedDocumentIds: Set<String>,
    val validate: (PortableCopyInput) -> Unit,
)

/** Prepare copy inputs from explicitly granted sources. Source locators stay in
 * memory, never in the portable manifest. No content or preferences are modified. */
internal object PortableSourcePlanner {
    fun prepare(live: PortableLiveLibrary, sources: Map<String, PortablePlanningSource>,
                coverSource: (String) -> PortablePlanningCover, checkActive: () -> Unit = {}): PortablePreparedSourcePlan {
        val inputs = mutableListOf<PortableCopyInput>()
        val checks = mutableMapOf<String, () -> Unit>()
        val variants = linkedMapOf<String, PortableVariantBinding>()
        val covers = linkedMapOf<String, String>()
        val protected = mutableSetOf<String>()
        var sequence = 0
        fun nextId(): String = "file-${(++sequence).toString().padStart(6, '0')}"
        fun validStamp(stamp: PortableSourceStamp) { require(stamp.bytes >= 0 && stamp.modified > 0) { "Source metadata unavailable" } }
        val grouped = live.games.groupBy { it.tree }.toSortedMap()
        grouped.entries.forEachIndexed { rootIndex, (treeKey, games) ->
            checkActive()
            val source = sources[treeKey] ?: error("Source permission unavailable")
            require(source.rootDocumentId.isNotBlank())
            protected += source.rootDocumentId
            val namespace = "source-${(rootIndex + 1).toString().padStart(4, '0')}"
            val selected = games.flatMap { game -> game.files.map { locator -> game to (locator to source.locate(locator, checkActive)) } }
            require(games.all { it.files.isNotEmpty() }) { "Game has no source file" }
            selected.forEach { (game, pair) ->
                val extension = pair.second.path.last().substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (game.platform != "PlayStation" && game.platform != "Dreamcast") require(extension !in setOf("cue", "gdi", "m3u") &&
                    !(game.platform == "Nintendo 3DS" && extension == "app")) { "Multi-file format requires additional support" }
                if (game.platform == "Dreamcast") require(extension != "m3u") { "Dreamcast playlists are not supported" }
                if (game.platform == "PlayStation") require(extension != "gdi") { "PlayStation GDI is not supported" }
            }
            val ps = selected.filter { it.first.platform == "PlayStation" }
            val dreamcast = selected.filter { it.first.platform == "Dreamcast" &&
                it.second.second.path.last().substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("gdi", "cue") }
            val needsDiscs = ps.any { it.second.second.path.last().substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("cue", "m3u") }
            fun stable(id: String, expectedPath: List<String>, stamp: PortableSourceStamp) {
                checkActive()
                val current = source.resolve(id, checkActive)
                check(current.path == expectedPath && current.documentId == id && source.stamp(id) == stamp) { "Source changed" }
            }
            fun descriptor(file: DiscImport.File): String {
                val path = file.path.split('/')
                val before = source.stamp(file.id).also(::validStamp)
                require(before.bytes <= DiscImport.MAX_DESCRIPTOR_BYTES)
                stable(file.id, path, before)
                val bytes = source.open(file.id).use { stream ->
                    val out = ByteArrayOutputStream(); val buffer = ByteArray(4096)
                    while (true) {
                        checkActive(); val count = stream.read(buffer)
                        if (count == -1) break
                        require(count > 0 && count <= DiscImport.MAX_DESCRIPTOR_BYTES - out.size())
                        out.write(buffer, 0, count)
                    }
                    out.toByteArray()
                }
                check(bytes.size.toLong() == before.bytes)
                stable(file.id, path, before)
                return Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            }
            val discFiles by lazy { source.files(checkActive) }
            val discPlan = if (needsDiscs) PortableDiscPlanner.build(discFiles,
                ps.map { it.second.second.path.joinToString("/") }.distinct(), ::descriptor, checkActive) else null
            val dreamcastPlan = if (dreamcast.isNotEmpty()) PortableDreamcastDiscPlanner.build(discFiles,
                dreamcast.map { it.second.second.path.joinToString("/") }.distinct(), ::descriptor, checkActive) else null
            val copiedByDoc = linkedMapOf<String, String>()
            fun add(file: PortableSourceFile): String = copiedByDoc.getOrPut(file.documentId) {
                checkActive()
                val stamp = source.stamp(file.documentId).also(::validStamp)
                stable(file.documentId, file.path, stamp)
                val id = nextId()
                inputs += PortableCopyInput(id, listOf("games", namespace) + file.path, stamp.bytes) { source.open(file.documentId) }
                checks[id] = { stable(file.documentId, file.path, stamp) }
                protected += file.documentId
                id
            }
            val psDependencies = mutableMapOf<String, List<String>>()
            discPlan?.selected?.forEach { (path, files) ->
                psDependencies[path] = files.map { file ->
                    val resolved = source.resolve(file.id, checkActive)
                    require(resolved.path == file.path.split('/'))
                    add(resolved)
                }
            }
            val dreamcastDependencies = mutableMapOf<String, List<String>>()
            dreamcastPlan?.selected?.forEach { (path, files) ->
                dreamcastDependencies[path] = files.map { file ->
                    val resolved = source.resolve(file.id, checkActive)
                    require(resolved.path == file.path.split('/'))
                    add(resolved)
                }
            }
            selected.forEach { (game, pair) ->
                val (locator, file) = pair
                val fileId = add(file)
                val deps = when (game.platform) {
                    "PlayStation" -> psDependencies[file.path.joinToString("/")]?.drop(1)
                    "Dreamcast" -> dreamcastDependencies[file.path.joinToString("/")]?.drop(1)
                    else -> null
                }
                val binding = PortableVariantBinding(fileId, deps)
                val previous = variants.putIfAbsent(locator, binding)
                require(previous == null || previous == binding) { "Ambiguous source locator" }
            }
        }
        val keys = live.covers.values.flatMap { listOf(it.manual, it.original, it.wide) }.filter { it.isNotEmpty() }.distinct()
        keys.forEach { key ->
            checkActive()
            val digest = managedCoverDigest(key) ?: error("Invalid cover reference")
            val source = coverSource(key)
            val stamp = source.stamp().also(::validStamp)
            val id = nextId()
            inputs += PortableCopyInput(id, listOf("covers", "$digest.image"), stamp.bytes) { source.open() }
            checks[id] = { checkActive(); check(source.stamp() == stamp) { "Cover changed" } }
            covers[key] = id
        }
        return PortablePreparedSourcePlan(inputs.toList(), variants.toMap(), covers.toMap(), protected.toSet()) { input ->
            checks[input.id]?.invoke() ?: error("Unknown copy input")
        }
    }
}
