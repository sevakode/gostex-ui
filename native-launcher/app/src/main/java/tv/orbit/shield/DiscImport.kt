package tv.orbit.shield

import java.util.Locale

/** Validates explicit disc descriptors within one already enumerated SAF root.
 *
 * Paths are document display names, never provider IDs or filesystem paths. Nothing is
 * guessed from '(Disc 1)' filenames: only a valid M3U joins discs into one launch entry.
 * A launch entry remains one Game.files variant; its tracks/discs stay in the descriptor.
 */
internal object DiscImport {
    const val MAX_DESCRIPTOR_BYTES = 128 * 1024
    private const val MAX_PLAYLIST_ENTRIES = 64
    private const val MAX_PLAYLIST_DEPTH = 16
    private val singleImages = setOf("chd", "pbp", "exe")
    private val discImages = setOf("chd", "pbp", "cue")
    private val trackModes = setOf("AUDIO", "MODE1_RAW", "MODE1/2048", "MODE1/2352", "MODE2_RAW",
        "MODE2/2336", "MODE2/2048", "MODE2/2324", "MODE2/2342", "MODE2/2332", "MODE2/2352")
    private val metadataCommands = setOf("CATALOG", "CD_DA", "CD_ROM", "CD_ROM_XA", "CD_TEXT",
        "CDTEXTFILE", "ISRC", "TRACK_ISRC", "TITLE", "PERFORMER", "SONGWRITER", "COMPOSER",
        "ARRANGER", "MESSAGE", "DISC_ID", "GENRE", "TOC_INFO1", "TOC_INFO2", "UPC_EAN", "SIZE_INFO")

    data class File(val path: String, val id: String) {
        val extension: String get() = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
    }
    enum class Reason { MALFORMED, MISSING, CASE_MISMATCH, OUTSIDE_ROOT, AMBIGUOUS, NESTED_PLAYLIST, CYCLE, TOO_LARGE, UNSUPPORTED }
    data class Problem(val path: String, val reason: Reason, val reference: String = "")
    data class Result(val entries: List<File>, val problems: List<Problem>)
    class Invalid(val reason: Reason, val reference: String = "", val contextual: Boolean = false) : IllegalArgumentException()
    private data class Validated(val file: File, val references: Set<String>)

    fun inspect(files: List<File>, readText: (File) -> String, checkpoint: () -> Unit = {}, providerSupported: Boolean = true): Result {
        val index = files.groupBy { it.path }
        val folded = files.groupBy { it.path.lowercase(Locale.ROOT) }
        val valid = mutableMapOf<String, Validated>()
        val invalid = mutableMapOf<String, Invalid>()
        val active = linkedSetOf<String>()

        fun resolve(owner: File, reference: String): File {
            val path = resolveRelative(owner.path, reference)
            val matches = index[path]
            if (matches == null) {
                val reason = if (folded.containsKey(path.lowercase(Locale.ROOT))) Reason.CASE_MISMATCH else Reason.MISSING
                throw Invalid(reason, reference)
            }
            if (matches.size != 1) throw Invalid(Reason.AMBIGUOUS, reference)
            return matches.single()
        }

        fun validate(file: File, depth: Int): Validated {
            checkpoint()
            if (!providerSupported) throw Invalid(Reason.UNSUPPORTED)
            valid[file.path]?.let { return it }
            invalid[file.path]?.let { throw it }
            if (depth > MAX_PLAYLIST_DEPTH) throw Invalid(Reason.TOO_LARGE, file.path, contextual = true)
            if (!active.add(file.path)) throw Invalid(Reason.CYCLE, file.path)
            try {
                if (index[file.path]?.size != 1) throw Invalid(Reason.AMBIGUOUS)
                val references = linkedSetOf<String>()
                when (file.extension) {
                    in singleImages -> Unit
                    "cue" -> {
                        cueReferences(readText(file)).forEach { reference ->
                            checkpoint()
                            val track = resolve(file, reference)
                            // FILE chooses BINARY/WAVE data regardless of filename extension.
                            // Known containers/descriptors are not raw sector/audio tracks;
                            // a malformed CUE must not consume another valid game card.
                            if (track.extension in singleImages || track.extension in setOf("cue", "m3u") || track.id == file.id) throw Invalid(Reason.UNSUPPORTED, reference)
                            references += track.path
                        }
                    }
                    "m3u" -> {
                        val paths = playlistReferences(readText(file))
                        for (reference in paths) {
                            checkpoint()
                            val disc = resolve(file, reference)
                            if (disc.extension != "m3u" && disc.extension !in discImages) throw Invalid(Reason.UNSUPPORTED, reference)
                            val child = validate(disc, depth + 1)
                            // DuckStation exposes one level of subimages. Passing nested M3U
                            // loses reliable disc selection; reject it rather than flattening or
                            // changing the owner's descriptor/save identity behind their back.
                            if (disc.extension == "m3u") throw Invalid(Reason.NESTED_PLAYLIST, reference)
                            references += disc.path
                            references += child.references
                        }
                    }
                    else -> throw Invalid(Reason.UNSUPPORTED)
                }
                return Validated(file, references).also { valid[file.path] = it }
            } catch (e: Invalid) {
                // A parent's recursion budget says nothing about a child launched on its
                // own. Keep intrinsic failures cached, but do not poison valid leaf lists.
                if (depth == 0 || !e.contextual) invalid[file.path] = e
                throw e
            } finally {
                active.remove(file.path)
            }
        }

        val problems = mutableListOf<Problem>()
        val candidates = files.filter { it.extension in singleImages || it.extension == "cue" || it.extension == "m3u" }
            .sortedBy { it.path }
        candidates.forEach { file ->
            try { validate(file, 0) }
            catch (e: Invalid) { problems += Problem(file.path, e.reason, e.reference) }
        }
        // Only a successfully validated descriptor can consume entries. A broken playlist
        // does not hide an otherwise usable disc, including those in other game folders.
        val consumed = valid.values.flatMap { it.references }.toSet()
        return Result(candidates.filter { it.path in valid && it.path !in consumed }.distinctBy { it.path }, problems)
    }

    /** Resolve '..' only while it remains inside the selected root. No URI/drive/absolute paths. */
    fun resolveRelative(owner: String, reference: String): String {
        if (reference.isEmpty() || reference.startsWith('/') ||
            reference.contains('\\') || reference.contains(':') || reference.any { it.code < 32 } ||
            reference.contains('"')) throw Invalid(Reason.OUTSIDE_ROOT, reference)
        val parts = owner.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }.toMutableList()
        reference.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) throw Invalid(Reason.OUTSIDE_ROOT, reference) else parts.removeAt(parts.lastIndex)
                else -> parts += segment
            }
        }
        if (parts.isEmpty()) throw Invalid(Reason.MALFORMED, reference)
        return parts.joinToString("/")
    }

    private fun lines(text: String): List<String> {
        if (text.length > MAX_DESCRIPTOR_BYTES) throw Invalid(Reason.TOO_LARGE)
        if (text.contains('\u0000') || text.contains('\uFFFD') || text.startsWith('\uFEFF')) throw Invalid(Reason.MALFORMED)
        return text.lineSequence().toList()
    }

    /** M3U paths with spaces are unquoted; quotation marks are literal in DuckStation's reader. */
    fun playlistReferences(text: String): List<String> {
        val paths = lines(text).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith('#') }
        if (paths.isEmpty()) throw Invalid(Reason.MALFORMED)
        if (paths.size > MAX_PLAYLIST_ENTRIES) throw Invalid(Reason.TOO_LARGE)
        if (paths.any { '"' in it }) throw Invalid(Reason.MALFORMED)
        return paths
    }

    fun cueReferences(text: String): List<String> {
        val references = mutableListOf<String>()
        var fileSeen = false
        var fileHasTrack = false
        var trackNumber = 0
        var lastTrackNumber = 0
        var pregap = false
        var indices = linkedMapOf<Int, Int>()
        var fileLastStart = -1
        var fileName = ""
        var finishedTrack = 0
        fun finishTrack() {
            if (trackNumber != 0 && (1 !in indices || indices.entries.zipWithNext().any { (a, b) -> a.key >= b.key || a.value > b.value }))
                throw Invalid(Reason.MALFORMED)
            if (trackNumber != 0 && trackNumber != finishedTrack) {
                val start = indices.getValue(1)
                if (start < fileLastStart || indices.getOrDefault(0, start) < fileLastStart) throw Invalid(Reason.MALFORMED)
                fileLastStart = start; finishedTrack = trackNumber
            }
        }
        for (sourceLine in lines(text)) {
            val line = sourceLine.trim()
            if (line.isEmpty() || line.startsWith(';') || line.startsWith("//")) continue
            val command = line.takeWhile { !it.isWhitespace() }.uppercase(Locale.ROOT)
            // DuckStation ignores the remainder of comments/metadata, including arbitrary
            // quotes. Read the command first so a tab after REM behaves like a space.
            if (command == "REM" || command in metadataCommands) continue
            val tokens = tokens(line)
            when (command) {
                "REM" -> Unit
                "FILE" -> {
                    finishTrack()
                    if (tokens.size != 3 || tokens[1].isEmpty() || tokens[2].uppercase(Locale.ROOT) !in setOf("BINARY", "WAVE") || (fileSeen && !fileHasTrack)) throw Invalid(Reason.MALFORMED)
                    references += tokens[1]
                    if (tokens[1] != fileName) fileLastStart = -1
                    fileName = tokens[1]
                    fileSeen = true; fileHasTrack = false; trackNumber = 0; indices = linkedMapOf()
                }
                "TRACK" -> {
                    finishTrack()
                    val number = tokens.getOrNull(1)?.toIntOrNull() ?: 0
                    if (!fileSeen || tokens.size != 3 || number !in 1..99 || number != lastTrackNumber + 1 || tokens[2].uppercase(Locale.ROOT) !in trackModes) throw Invalid(Reason.MALFORMED)
                    trackNumber = number; lastTrackNumber = number; fileHasTrack = true; pregap = false; indices = linkedMapOf()
                }
                "INDEX" -> {
                    val index = tokens.getOrNull(1)?.toIntOrNull() ?: -1
                    if (trackNumber == 0 || tokens.size != 3 || index !in 0..99 || index in indices) throw Invalid(Reason.MALFORMED)
                    indices[index] = msf(tokens[2])
                }
                "PREGAP", "POSTGAP" -> {
                    if (trackNumber == 0 || tokens.size != 2 || (command == "PREGAP" && pregap)) throw Invalid(Reason.MALFORMED)
                    msf(tokens[1]); if (command == "PREGAP") pregap = true
                }
                "FLAGS" -> if (trackNumber == 0 || tokens.size < 2 || tokens.drop(1).any { it.uppercase(Locale.ROOT) !in setOf("PRE", "DCP", "4CH", "SCMS") }) throw Invalid(Reason.MALFORMED)
                "COPY", "PRE_EMPHASIS", "FOUR_CHANNEL_AUDIO", "TWO_CHANNEL_AUDIO" -> if (trackNumber == 0 || tokens.size != 1) throw Invalid(Reason.MALFORMED)
                "NO" -> if (trackNumber == 0 || tokens.size != 2 || tokens[1].uppercase(Locale.ROOT) !in setOf("COPY", "PRE_EMPHASIS")) throw Invalid(Reason.MALFORMED)
                in metadataCommands -> Unit
                else -> throw Invalid(Reason.MALFORMED)
            }
        }
        finishTrack()
        if (!fileSeen || !fileHasTrack || lastTrackNumber == 0) throw Invalid(Reason.MALFORMED)
        return references.distinct()
    }

    private fun msf(value: String): Int {
        val parts = value.split(':')
        if (parts.size != 3 || parts.any { it.isEmpty() || it.any { c -> !c.isDigit() } }) throw Invalid(Reason.MALFORMED)
        val minute = parts[0].toIntOrNull() ?: throw Invalid(Reason.MALFORMED)
        val second = parts[1].toIntOrNull() ?: throw Invalid(Reason.MALFORMED)
        val frame = parts[2].toIntOrNull() ?: throw Invalid(Reason.MALFORMED)
        if (minute !in 0..255 || second !in 0..59 || frame !in 0..74) throw Invalid(Reason.MALFORMED)
        return (minute * 60 + second) * 75 + frame
    }

    private fun tokens(line: String): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        while (i < line.length) {
            if (line[i].isWhitespace()) { i++; continue }
            if (line[i] == '"') {
                val end = line.indexOf('"', i + 1)
                if (end < 0 || (end + 1 < line.length && !line[end + 1].isWhitespace())) throw Invalid(Reason.MALFORMED)
                result += line.substring(i + 1, end); i = end + 1
            } else {
                val start = i
                while (i < line.length && !line[i].isWhitespace()) {
                    if (line[i] == '"') throw Invalid(Reason.MALFORMED)
                    i++
                }
                result += line.substring(start, i)
            }
        }
        return result
    }
}
