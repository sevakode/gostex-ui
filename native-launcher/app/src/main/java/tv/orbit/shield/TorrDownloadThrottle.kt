package tv.orbit.shield

/** Limits GOSTEX writes only. TorrServer and other processes remain independent. */
internal class TorrDownloadThrottle(
    private val rateKiB: () -> Int,
    private val now: () -> Long = System::nanoTime,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    private var lastRate = 0
    private var deadline = 0L

    fun beforeWrite(bytes: Int, cancelled: () -> Boolean): Boolean {
        require(bytes in 1..65536)
        while (true) {
            if (cancelled() || Thread.currentThread().isInterrupted) return false
            val rate = rateKiB()
            val time = now()
            if (rate <= 0) { lastRate = 0; deadline = time; return true }
            if (rate != lastRate) { lastRate = rate; deadline = time }
            // A delayed source cannot accumulate a burst of write credit.
            if (deadline < time) deadline = time
            deadline += bytes.toLong() * 1_000_000_000L / (rate.toLong() * 1024)
            while (true) {
                if (cancelled() || Thread.currentThread().isInterrupted) return false
                if (rateKiB() != rate) break
                val remaining = deadline - now()
                // Accumulate sub-millisecond debt; sleeping for every tiny
                // network read would otherwise impose a much lower rate.
                if (remaining <= 1_000_000L) return true
                try { sleep(minOf(remaining / 1_000_000L, 100L)) }
                catch (_: InterruptedException) { Thread.currentThread().interrupt(); return false }
            }
        }
    }
}
