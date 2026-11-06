package tv.orbit.shield

/** A preview only: conflicts must be resolved by an explicit user choice before any write.
 * Values may contain local paths; do not log or expose them as diagnostic messages. */
internal data class PortableRestoreMergeResult(
    val games: List<Game>,
    val roots: List<LibraryRootRecord>,
    val covers: Map<String, ManagedCover>,
    val choices: Map<String, String>,
    val home: HomeSectionsOptions?,
    val settings: Map<String, Any>,
    val conflicts: Set<PortableRestoreConflict>,
) {
    val ready: Boolean get() = conflicts.isEmpty() && home != null
}

internal data class PortableRestoreConflict(val kind: Kind, val key: String) {
    enum class Kind { GAME, ROOT, COVER, COVER_CHOICE, HOME, SETTING }
}

/** Never resolves competing user choices in code. Existing items retain their order;
 * non-conflicting restored items are appended. A null home or a nonempty conflict set
 * means the result is incomplete and cannot be applied. No data is read or written. */
internal object PortableRestoreMerge {
    fun prepare(live: PortableLiveLibrary, roots: List<LibraryRootRecord>, restore: PortableRestorePlan): PortableRestoreMergeResult {
        val conflicts = linkedSetOf<PortableRestoreConflict>()
        fun conflict(kind: PortableRestoreConflict.Kind, key: String) { conflicts += PortableRestoreConflict(kind, key) }
        require(live.games.map { it.id }.distinct().size == live.games.size) { "Duplicate live game IDs" }
        require(restore.games.map { it.id }.distinct().size == restore.games.size) { "Duplicate restored game IDs" }
        require(roots.map { it.platform to it.uri }.distinct().size == roots.size) { "Duplicate live roots" }
        require(restore.roots.map { it.platform to it.treeUri }.distinct().size == restore.roots.size) { "Duplicate restored roots" }

        val games = live.games.toMutableList()
        val gameById = live.games.associateBy { it.id }
        restore.games.forEach { game ->
            val old = gameById[game.id]
            if (old == null) games += game else if (old != game) conflict(PortableRestoreConflict.Kind.GAME, game.id)
        }
        val mergedRoots = roots.toMutableList()
        val rootByKey = roots.associateBy { it.platform to it.uri }
        restore.roots.forEach { root ->
            val candidate = LibraryRootRecord(root.platform, root.treeUri, LibrarySource.PORTABLE_SNAPSHOT)
            val old = rootByKey[root.platform to root.treeUri]
            if (old == null) mergedRoots += candidate else if (old != candidate) conflict(PortableRestoreConflict.Kind.ROOT, root.platform)
        }
        fun <T> mergeMap(existing: Map<String, T>, incoming: Map<String, T>, kind: PortableRestoreConflict.Kind): Map<String, T> {
            val merged = existing.toMutableMap()
            incoming.forEach { (key, value) ->
                if (existing.containsKey(key) && existing[key] != value) conflict(kind, key)
                else merged[key] = value
            }
            return merged.toMap()
        }
        val covers = mergeMap(live.covers, restore.covers, PortableRestoreConflict.Kind.COVER)
        val choices = mergeMap(live.coverChoices, restore.coverChoices, PortableRestoreConflict.Kind.COVER_CHOICE)
        val settings = mergeMap(live.settings, restore.snapshot.settings, PortableRestoreConflict.Kind.SETTING)
        val home = if (live.home == restore.snapshot.home) live.home else null.also {
            conflict(PortableRestoreConflict.Kind.HOME, "home.sections.v1")
        }
        return PortableRestoreMergeResult(games, mergedRoots, covers, choices, home, settings, conflicts)
    }
}

/** Decisions are ephemeral, tied to this preview and must be obtained again after the
 * underlying state changes. They never authorize touching ROM files or clearing data. */
internal enum class PortableRestoreChoice { KEEP_CURRENT, USE_BACKUP }

internal fun resolvePortableRestore(
    live: PortableLiveLibrary,
    roots: List<LibraryRootRecord>,
    restore: PortableRestorePlan,
    decisions: Map<PortableRestoreConflict, PortableRestoreChoice>,
): PortableRestoreMergeResult {
    val draft = PortableRestoreMerge.prepare(live, roots, restore)
    require(decisions.keys.all { it in draft.conflicts }) { "Decision does not match current conflicts" }
    require(decisions.keys.none { it.kind == PortableRestoreConflict.Kind.ROOT }) { "Root mode conflict cannot be resolved by replacement" }
    val gameConflicts = draft.conflicts.filter { it.kind == PortableRestoreConflict.Kind.GAME }.map { it.key }.toSet()
    require(decisions.keys.none { it.key in gameConflicts && it.kind in setOf(PortableRestoreConflict.Kind.COVER, PortableRestoreConflict.Kind.COVER_CHOICE) }) {
        "Game and its cover must be resolved together"
    }
    val remaining = draft.conflicts.toMutableSet()
    val games = draft.games.toMutableList()
    val gameIndices = games.withIndex().associate { it.value.id to it.index }
    val restoredGames = restore.games.associateBy { it.id }
    val covers = draft.covers.toMutableMap()
    val choices = draft.choices.toMutableMap()
    val settings = draft.settings.toMutableMap()
    var home = draft.home
    fun <T> selectEntry(target: MutableMap<String, T>, source: Map<String, T>, key: String) {
        if (source.containsKey(key)) target[key] = source.getValue(key) else target.remove(key)
    }
    decisions.forEach { (conflict, choice) ->
        val incoming = choice == PortableRestoreChoice.USE_BACKUP
        when (conflict.kind) {
            PortableRestoreConflict.Kind.GAME -> {
                if (incoming) {
                    val game = restoredGames.getValue(conflict.key)
                    games[gameIndices.getValue(conflict.key)] = game
                }
                selectEntry(covers, if (incoming) restore.covers else live.covers, conflict.key)
                selectEntry(choices, if (incoming) restore.coverChoices else live.coverChoices, conflict.key)
                remaining.remove(PortableRestoreConflict(PortableRestoreConflict.Kind.COVER, conflict.key))
                remaining.remove(PortableRestoreConflict(PortableRestoreConflict.Kind.COVER_CHOICE, conflict.key))
            }
            PortableRestoreConflict.Kind.COVER -> selectEntry(covers, if (incoming) restore.covers else live.covers, conflict.key)
            PortableRestoreConflict.Kind.COVER_CHOICE -> selectEntry(choices, if (incoming) restore.coverChoices else live.coverChoices, conflict.key)
            PortableRestoreConflict.Kind.HOME -> home = if (incoming) restore.snapshot.home else live.home
            PortableRestoreConflict.Kind.SETTING -> selectEntry(settings, if (incoming) restore.snapshot.settings else live.settings, conflict.key)
            PortableRestoreConflict.Kind.ROOT -> error("Root mode conflict cannot be resolved by replacement")
        }
        remaining.remove(conflict)
    }
    return PortableRestoreMergeResult(games.toList(), draft.roots, covers.toMap(), choices.toMap(), home, settings.toMap(), remaining.toSet())
}
