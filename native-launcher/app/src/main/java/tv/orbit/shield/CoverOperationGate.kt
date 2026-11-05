package tv.orbit.shield

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Slow shared-folder IO serializes with other syncs, not with local edits. */
internal class CoverOperationGate {
    private val mutations = Mutex()
    private val syncs = Mutex()

    suspend fun <T> mutate(block: suspend () -> T): T = mutations.withLock { block() }
    suspend fun <T> synchronize(block: suspend () -> T): T = syncs.withLock { block() }
}

/** Shared updates may replace generated sources, never a user's manual selection. */
internal fun mergeSharedCover(current: ManagedCover, original: String?, wide: String?): ManagedCover =
    current.copy(original = original ?: current.original, wide = wide ?: current.wide)
