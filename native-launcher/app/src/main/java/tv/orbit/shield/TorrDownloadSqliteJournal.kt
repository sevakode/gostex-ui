package tv.orbit.shield

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import java.io.IOException

/** Internal operational state. User preferences and portable content stay in their existing stores. */
internal class TorrDownloadSqliteJournal private constructor(
    private val database: SQLiteDatabase,
) : TorrDownloadJournal {
    private data class Row(val job: TorrDownloadJob, val ordinal: Long, val payload: String)
    private data class Snapshot(val revision: Long, val rows: List<Row>)
    private var snapshot: Snapshot? = null
    private var closed = false

    @Synchronized override fun read(): List<TorrDownloadJob> = guarded("Cannot read download journal") {
        requireOpen()
        val loaded = transaction(database) {
            val revision = readRevision(database)
            val rows = ArrayList<Row>()
            var activeCharacters = 1L
            var completedCharacters = 1L
            database.rawQuery(
                "SELECT id, ordinal, length(payload), " +
                    "CASE WHEN length(payload) <= $INLINE_CHARACTERS THEN payload ELSE NULL END, " +
                    "typeof(id), typeof(ordinal), typeof(payload) " +
                    "FROM download_jobs ORDER BY ordinal LIMIT ${TorrDownloadJournalCodec.MAX_RECORDS + 1}",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    require(rows.size < TorrDownloadJournalCodec.MAX_RECORDS) { "Too many download records" }
                    require(cursor.getString(4) == "text" && cursor.getString(5) == "integer" && cursor.getString(6) == "text")
                    val id = cursor.getString(0)
                    val ordinal = cursor.getLong(1)
                    require(ordinal >= 0 && (rows.isEmpty() || ordinal > rows.last().ordinal))
                    val characters = cursor.getLong(2)
                    require(characters in 1..TorrDownloadJournalCodec.MAX_JOURNAL_BYTES.toLong())
                    val payload = if (cursor.isNull(3)) readLargePayload(id, characters) else cursor.getString(3)
                    require(payload.length <= TorrDownloadJournalCodec.MAX_JOURNAL_BYTES)
                    val job = TorrDownloadJournalCodec.decode(payload)
                    require(job.id == id)
                    if (job.state == "done") {
                        completedCharacters += payload.length + 1L
                        require(completedCharacters <= TorrDownloadJournalCodec.MAX_JOURNAL_BYTES)
                    } else {
                        activeCharacters += payload.length + 1L
                        require(activeCharacters <= TorrDownloadJournalCodec.MAX_JOURNAL_BYTES)
                    }
                    rows += Row(job, ordinal, payload)
                }
            }
            validateRows(rows)
            Snapshot(revision, rows.toList())
        }
        snapshot = loaded
        loaded.rows.map { it.job }
    }

    @Synchronized override fun write(previous: List<TorrDownloadJob>, next: List<TorrDownloadJob>) {
        guarded("Cannot save download journal") {
            requireOpen()
            val current = snapshot ?: throw IOException("Read download journal before writing")
            require(previous == current.rows.map { it.job }) { "Stale download journal snapshot" }
            require(next.size <= TorrDownloadJournalCodec.MAX_RECORDS)
            val oldById = current.rows.associateBy { it.job.id }
            val nextIds = next.map { it.id }.toSet()
            val retained = current.rows.filter { it.job.id in nextIds }
            val existingPrefix = next.takeWhile { it.id in oldById }
            // The facade appends, removes and maps records. Preserve all ordinals for
            // those operations; only a future explicit reorder needs a bounded rebuild.
            val keepsOrder = existingPrefix.map { it.id } == retained.map { it.job.id } &&
                next.drop(existingPrefix.size).none { it.id in oldById }
            val addedCount = next.size - existingPrefix.size
            val lastOrdinal = current.rows.lastOrNull()?.ordinal ?: -1L
            val rebuild = !keepsOrder || lastOrdinal > Long.MAX_VALUE - addedCount
            var appendedOrdinal = lastOrdinal
            val updatedRows = next.mapIndexed { index, job ->
                val old = oldById[job.id]
                val payload = if (old?.job == job) old.payload else {
                    TorrDownloadJournalCodec.validate(listOf(job))
                    TorrDownloadJournalCodec.encode(job)
                }
                val ordinal = when {
                    rebuild -> index.toLong()
                    old != null -> old.ordinal
                    else -> ++appendedOrdinal
                }
                Row(job, ordinal, payload)
            }
            validateRows(updatedRows)
            transaction(database) {
                require(readRevision(database) == current.revision) { "Stale download journal revision" }
                if (next == previous) return@transaction
                check(current.revision < Long.MAX_VALUE) { "Download journal revision exhausted" }
                if (rebuild) {
                    database.delete("download_jobs", null, null)
                    updatedRows.forEach { insertRow(database, it) }
                } else {
                    current.rows.filter { it.job.id !in nextIds }.forEach { row ->
                        check(database.delete("download_jobs", "id = ?", arrayOf(row.job.id)) == 1)
                    }
                    updatedRows.forEach { row ->
                        val old = oldById[row.job.id]
                        when {
                            old == null -> insertRow(database, row)
                            old.payload != row.payload -> {
                                val values = ContentValues(1).apply { put("payload", row.payload) }
                                check(database.update("download_jobs", values, "id = ?", arrayOf(row.job.id)) == 1)
                            }
                        }
                    }
                }
                val revision = ContentValues(1).apply { put("revision", current.revision + 1) }
                check(database.update("journal_meta", revision, "singleton = 1 AND revision = ?", arrayOf(current.revision.toString())) == 1)
            }
            // Nothing observable changes until endTransaction has confirmed commit.
            snapshot = Snapshot(if (next == previous) current.revision else current.revision + 1, updatedRows)
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        snapshot = null
        database.close()
    }

    /** SQLite SUBSTR counts code points; Kotlin's length is checked separately. */
    private fun readLargePayload(id: String, characters: Long): String {
        val result = StringBuilder()
        var start = 1L
        while (start <= characters) {
            val part = database.rawQuery(
                "SELECT substr(payload, ?, ?) FROM download_jobs WHERE id = ?",
                arrayOf(start.toString(), CHUNK_CHARACTERS.toString(), id),
            ).use { cursor ->
                check(cursor.moveToFirst() && !cursor.isNull(0))
                cursor.getString(0).also { check(!cursor.moveToNext()) }
            }
            require(part.isNotEmpty() && result.length <= TorrDownloadJournalCodec.MAX_JOURNAL_BYTES - part.length)
            result.append(part)
            start += CHUNK_CHARACTERS
        }
        return result.toString()
    }

    private fun requireOpen() { check(!closed) { "Download journal is closed" } }

    companion object {
        private const val DATABASE_NAME = "torr-downloads.db"
        private const val SCHEMA_VERSION = 1
        private const val INLINE_CHARACTERS = 32 * 1024
        private const val CHUNK_CHARACTERS = 64 * 1024
        private const val CREATE_JOBS = "CREATE TABLE download_jobs (id TEXT PRIMARY KEY NOT NULL, ordinal INTEGER NOT NULL UNIQUE, payload TEXT NOT NULL)"
        private const val CREATE_META = "CREATE TABLE journal_meta (singleton INTEGER PRIMARY KEY NOT NULL CHECK (singleton = 1), migrated INTEGER NOT NULL CHECK (migrated = 1), revision INTEGER NOT NULL CHECK (revision >= 0))"
        private val openLock = Any()

        /** Called by the facade's I/O readiness path under its portable-maintenance gate. */
        fun open(context: Context): TorrDownloadJournal = synchronized(openLock) {
            guarded("Cannot open download journal") {
                val path = context.getDatabasePath(DATABASE_NAME)
                val parent = path.parentFile ?: throw IOException("Missing download journal directory")
                if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create download journal directory")
                // SQLiteOpenHelper's default corruption handler deletes the original
                // file. This journal must retain it and surface an error instead.
                val corruptionHandler = DatabaseErrorHandler {
                    throw SQLiteDatabaseCorruptException("Download journal is corrupt; original retained")
                }
                val db = SQLiteDatabase.openDatabase(
                    path.absolutePath, null,
                    SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                    corruptionHandler,
                )
                try {
                    db.rawQuery("PRAGMA journal_mode=DELETE", null).use { cursor ->
                        check(cursor.moveToFirst() && cursor.getString(0).equals("delete", ignoreCase = true))
                    }
                    db.execSQL("PRAGMA synchronous=FULL")
                    db.rawQuery("PRAGMA synchronous", null).use { cursor -> check(cursor.moveToFirst() && cursor.getInt(0) == 2) }
                    val source = transaction(db) {
                        when (db.version) {
                            0 -> {
                                db.rawQuery("SELECT name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' AND name != 'android_metadata' LIMIT 1", null).use {
                                    require(!it.moveToFirst()) { "Unrecognized download journal schema" }
                                }
                                val prefs = context.getSharedPreferences("orbit", Context.MODE_PRIVATE)
                                val active = prefs.getString(TorrDownloadJournalCodec.ACTIVE_KEY, null)
                                val completed = prefs.getString(TorrDownloadJournalCodec.COMPLETED_KEY, null)
                                val jobs = TorrDownloadJournalCodec.readLegacy(active, completed)
                                val rows = jobs.mapIndexed { index, job -> Row(job, index.toLong(), TorrDownloadJournalCodec.encode(job)) }
                                validateRows(rows)
                                db.execSQL(CREATE_JOBS)
                                db.execSQL(CREATE_META)
                                rows.forEach { insertRow(db, it) }
                                db.execSQL("INSERT INTO journal_meta (singleton, migrated, revision) VALUES (1, 1, 0)")
                                db.version = SCHEMA_VERSION
                                LegacySource(prefs, active, completed)
                            }
                            SCHEMA_VERSION -> {
                                validateSchema(db)
                                readRevision(db)
                                null
                            }
                            else -> throw IOException("Unsupported download journal schema")
                        }
                    }
                    // A crash or failed preferences commit here leaves stale legacy
                    // keys. The committed marker prevents their re-import forever.
                    source?.removeAfterCommit()
                    TorrDownloadSqliteJournal(db)
                } catch (failure: Throwable) {
                    try { db.close() } catch (_: Exception) { }
                    throw failure
                }
            }
        }

        private data class LegacySource(val prefs: SharedPreferences, val active: String?, val completed: String?) {
            fun removeAfterCommit() {
                if (active == null && completed == null) return
                try {
                    if (prefs.getString(TorrDownloadJournalCodec.ACTIVE_KEY, null) == active &&
                        prefs.getString(TorrDownloadJournalCodec.COMPLETED_KEY, null) == completed) {
                        prefs.edit().remove(TorrDownloadJournalCodec.ACTIVE_KEY).remove(TorrDownloadJournalCodec.COMPLETED_KEY).commit()
                    }
                } catch (_: Exception) {
                    // The database commit is authoritative. Never undo it or import
                    // the source again because optional legacy cleanup failed.
                }
            }
        }

        private fun validateRows(rows: List<Row>) {
            TorrDownloadJournalCodec.validateStructure(rows.map { it.job })
            var activeCharacters = 2L
            var completedCharacters = 2L
            rows.forEach { row ->
                require(row.payload.length <= TorrDownloadJournalCodec.MAX_JOURNAL_BYTES)
                if (row.job.state == "done") completedCharacters += row.payload.length + 1L
                else activeCharacters += row.payload.length + 1L
            }
            // JSON arrays use one comma between records, not after the final row.
            if (rows.any { it.job.state == "done" }) completedCharacters--
            if (rows.any { it.job.state != "done" }) activeCharacters--
            require(activeCharacters <= TorrDownloadJournalCodec.MAX_JOURNAL_BYTES && completedCharacters <= TorrDownloadJournalCodec.MAX_JOURNAL_BYTES)
        }

        private fun insertRow(db: SQLiteDatabase, row: Row) {
            val values = ContentValues(3).apply {
                put("id", row.job.id); put("ordinal", row.ordinal); put("payload", row.payload)
            }
            check(db.insertOrThrow("download_jobs", null, values) != -1L)
        }

        private fun readRevision(db: SQLiteDatabase): Long = db.rawQuery(
            "SELECT singleton, migrated, revision, typeof(revision) FROM journal_meta LIMIT 2", null,
        ).use { cursor ->
            require(cursor.moveToFirst() && cursor.getLong(0) == 1L && cursor.getLong(1) == 1L && cursor.getString(3) == "integer")
            cursor.getLong(2).also { require(it >= 0 && !cursor.moveToNext()) }
        }

        private fun validateSchema(db: SQLiteDatabase) {
            val expected = mapOf("download_jobs" to CREATE_JOBS, "journal_meta" to CREATE_META)
            expected.forEach { (table, sql) ->
                db.rawQuery("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table)).use { cursor ->
                    require(cursor.moveToFirst() && cursor.getString(0).replace(Regex("\\s+"), " ").trim().equals(sql, ignoreCase = true) && !cursor.moveToNext()) {
                        "Unrecognized download journal schema"
                    }
                }
            }
        }

        private inline fun <T> transaction(db: SQLiteDatabase, operation: () -> T): T {
            db.beginTransaction()
            try {
                val result = operation()
                db.setTransactionSuccessful()
                return result
            } finally {
                db.endTransaction()
            }
        }

        private inline fun <T> guarded(message: String, operation: () -> T): T = try {
            operation()
        } catch (cause: Exception) {
            if (cause is IOException) throw cause
            throw IOException(message, cause)
        }
    }
}
