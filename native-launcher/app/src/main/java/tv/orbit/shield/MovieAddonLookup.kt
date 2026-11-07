package tv.orbit.shield

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

// Shared across searches: cancelled blocking network calls can still be winding down.
private val addonLookupPermits = Semaphore(2)

/** Results arrive as each add-on finishes; the final list retains input order. */
internal suspend fun <T, R> lookupAddons(
    items: List<T>, onResult: suspend (Result<R>) -> Unit = {}, fetch: suspend (T) -> R
): List<Result<R>> = coroutineScope {
    items.map { item ->
        async {
            val result = addonLookupPermits.withPermit {
                try { Result.success(fetch(item)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { Result.failure(error) }
            }
            currentCoroutineContext().ensureActive()
            onResult(result)
            result
        }
    }.awaitAll()
}
