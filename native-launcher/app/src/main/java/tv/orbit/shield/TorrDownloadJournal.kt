package tv.orbit.shield

/** Durable queue operations. Callers serialize access and perform them on an I/O dispatcher. */
internal interface TorrDownloadJournal : AutoCloseable {
    fun read(): List<TorrDownloadJob>
    fun write(previous: List<TorrDownloadJob>, next: List<TorrDownloadJob>)
    override fun close()
}
