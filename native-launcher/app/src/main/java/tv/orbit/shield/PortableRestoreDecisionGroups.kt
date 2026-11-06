package tv.orbit.shield

/** Explicit bulk choices for one preview, never persisted as a user preference.
 * Conflicting games and their covers are always chosen together. */
internal enum class PortableRestoreDecisionGroup { GAMES_AND_COVERS, HOME, SETTINGS }

internal object PortableRestoreDecisionGroups {
    fun groups(conflicts: Set<PortableRestoreConflict>): Set<PortableRestoreDecisionGroup> = buildSet {
        conflicts.forEach { conflict ->
            when (conflict.kind) {
                PortableRestoreConflict.Kind.GAME, PortableRestoreConflict.Kind.COVER, PortableRestoreConflict.Kind.COVER_CHOICE -> add(PortableRestoreDecisionGroup.GAMES_AND_COVERS)
                PortableRestoreConflict.Kind.HOME -> add(PortableRestoreDecisionGroup.HOME)
                PortableRestoreConflict.Kind.SETTING -> add(PortableRestoreDecisionGroup.SETTINGS)
                PortableRestoreConflict.Kind.ROOT -> Unit
            }
        }
    }
    fun decisions(conflicts: Set<PortableRestoreConflict>, choices: Map<PortableRestoreDecisionGroup, PortableRestoreChoice>): Map<PortableRestoreConflict, PortableRestoreChoice> {
        require(choices.keys.all { it in groups(conflicts) }) { "Stale decision group" }
        val games = conflicts.filter { it.kind == PortableRestoreConflict.Kind.GAME }.map { it.key }.toSet()
        return buildMap {
            conflicts.forEach { conflict ->
                val group = when (conflict.kind) {
                    PortableRestoreConflict.Kind.GAME -> PortableRestoreDecisionGroup.GAMES_AND_COVERS
                    PortableRestoreConflict.Kind.COVER, PortableRestoreConflict.Kind.COVER_CHOICE ->
                        if (conflict.key in games) null else PortableRestoreDecisionGroup.GAMES_AND_COVERS
                    PortableRestoreConflict.Kind.HOME -> PortableRestoreDecisionGroup.HOME
                    PortableRestoreConflict.Kind.SETTING -> PortableRestoreDecisionGroup.SETTINGS
                    PortableRestoreConflict.Kind.ROOT -> null
                }
                group?.let { choices[it] }?.let { put(conflict, it) }
            }
        }
    }
}
