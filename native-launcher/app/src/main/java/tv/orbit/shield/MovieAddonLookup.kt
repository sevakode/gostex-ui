package tv.orbit.shield

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** A small bound keeps a slow add-on from serially blocking every other source. */
internal suspend fun <T, R> lookupAddons(items: List<T>, fetch: suspend (T) -> R): List<Result<R>> = coroutineScope {
    val permits = Semaphore(2)
    items.map { item ->
        async {
            permits.withPermit {
                try { Result.success(fetch(item)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { Result.failure(error) }
            }
        }
    }.awaitAll()
}
