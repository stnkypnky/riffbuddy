package com.riffbuddy.data

import com.riffbuddy.audio.Wav
import com.riffbuddy.domain.Recording
import com.riffbuddy.domain.TakeSource
import com.riffbuddy.platform.Blocking
import com.riffbuddy.platform.delete
import com.riffbuddy.platform.isFile
import com.riffbuddy.platform.list
import com.riffbuddy.platform.mkdirs
import com.riffbuddy.platform.size
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Path

/**
 * Takes: rows here, WAVs in [dir].
 *
 * The row is written when recording *starts* ([begin]) and finished when it
 * stops ([finish]). A take interrupted by the app being killed therefore still
 * has a row, and [repairAll] on the next launch fixes its WAV header and works
 * out its length from the file — it comes back rather than vanishing.
 */
class RecordingRepo(private val db: Db, val dir: Path) {

    fun fileFor(r: Recording) = dir / r.storedName

    suspend fun all(): List<Recording> = withContext(Dispatchers.Blocking) {
        db.readableDatabase.rawQuery("SELECT $COLUMNS FROM recordings ORDER BY created_at DESC", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.toRecording()) } }
    }

    suspend fun byId(id: Long): Recording? = withContext(Dispatchers.Blocking) {
        db.readableDatabase.rawQuery("SELECT $COLUMNS FROM recordings WHERE id = ?", arrayOf(id.toString()))
            .use { c -> if (c.moveToFirst()) c.toRecording() else null }
    }

    /** A new take's row and the file it will be written to. */
    suspend fun begin(
        name: String,
        sampleRate: Int,
        songId: Long?,
        sessionId: Long?,
        latencyOffsetMs: Int,
        source: TakeSource,
    ): Recording = withContext(Dispatchers.Blocking) {
        dir.mkdirs()
        val stored = "${Uuid.random()}.wav"
        val now = Clock.System.now().toEpochMilliseconds()
        val id = db.writableDatabase.insertOrThrow(
            "recordings", null,
            SqlValues().apply {
                if (songId != null) put("song_id", songId) else putNull("song_id")
                if (sessionId != null) put("session_id", sessionId) else putNull("session_id")
                put("name", name)
                put("stored_name", stored)
                put("sample_rate", sampleRate)
                put("latency_offset_ms", latencyOffsetMs)
                put("source", source.name.lowercase())
                put("created_at", now)
                put("updated_at", now)
            },
        )
        byId(id)!!
    }

    suspend fun finish(id: Long, durationMs: Long, sizeBytes: Long, alignMs: Long?): Unit = withContext(Dispatchers.Blocking) {
        update(id, SqlValues().apply {
            put("duration_ms", durationMs)
            put("size_bytes", sizeBytes)
            if (alignMs != null) put("align_ms", alignMs) else putNull("align_ms")
        })
    }

    /**
     * A trimmed copy of [from] as a take of its own: frames
     * [fromFrame, toFrame) of its file. The original is untouched. The clip
     * keeps the song link but not the play-along alignment, because its start
     * is wherever the player trimmed it.
     */
    suspend fun saveClip(from: Recording, fromFrame: Int, toFrame: Int, name: String): Recording = withContext(Dispatchers.Blocking) {
        val clip = begin(name, from.sampleRate, from.songId, sessionId = null, latencyOffsetMs = 0, source = TakeSource.NONE)
        val file = fileFor(clip)
        val frames = runCatching { Wav.slice(fileFor(from), fromFrame, toFrame, file) }.getOrElse { e ->
            db.writableDatabase.delete("recordings", "id = ?", arrayOf(clip.id.toString()))
            file.delete()
            throw e
        }
        finish(clip.id, durationMs = frames * 1000L / from.sampleRate, sizeBytes = file.size(), alignMs = null)
        byId(clip.id)!!
    }

    /**
     * Puts right any take left half-written by a crash: fixes the WAV header
     * and records the length the file really has. Run once at launch.
     */
    suspend fun repairAll(): Int = withContext(Dispatchers.Blocking) {
        var fixed = 0
        all().forEach { r ->
            val f = fileFor(r)
            if (!f.isFile) return@forEach
            val headerFixed = runCatching { Wav.repair(f) }.getOrDefault(false)
            if (headerFixed || r.durationMs == 0L) {
                val frames = (f.size() - Wav.HEADER_BYTES).coerceAtLeast(0) / 2
                update(r.id, SqlValues().apply {
                    put("duration_ms", frames * 1000 / r.sampleRate)
                    put("size_bytes", f.size())
                })
                fixed++
            }
        }
        // A take abandoned before a single sample — nothing to keep.
        db.writableDatabase.delete("recordings", "duration_ms = 0 AND size_bytes <= ?", arrayOf(Wav.HEADER_BYTES.toString()))
        fixed
    }

    suspend fun rename(id: Long, name: String): Unit = withContext(Dispatchers.Blocking) {
        name.trim().take(120).takeIf { it.isNotEmpty() }?.let { n -> update(id, SqlValues().apply { put("name", n) }) }
    }

    suspend fun setNotes(id: Long, notes: String): Unit = withContext(Dispatchers.Blocking) {
        update(id, SqlValues().apply { put("notes", notes) })
    }

    suspend fun setRating(id: Long, rating: Int?): Unit = withContext(Dispatchers.Blocking) {
        update(id, SqlValues().apply { if (rating != null) put("rating", rating.coerceIn(1, 5)) else putNull("rating") })
    }

    suspend fun setStarred(id: Long, starred: Boolean): Unit = withContext(Dispatchers.Blocking) {
        update(id, SqlValues().apply { put("starred", if (starred) 1 else 0) })
    }

    suspend fun link(id: Long, songId: Long?): Unit = withContext(Dispatchers.Blocking) {
        update(id, SqlValues().apply { if (songId != null) put("song_id", songId) else putNull("song_id") })
    }

    /**
     * The take, and any looper entry made from it with "Loop it".
     *
     * Deleting a take removes both its file and its row, the row first.
     *
     * The looper keeps a *reference* to a take's file, which is right for a
     * song the player owns elsewhere and wrong for a take: once the take is
     * deleted the file is gone for good, and the looper would be left offering
     * "Find it again" for a file nobody can find. The entry's saved loops go
     * with it (ON DELETE CASCADE).
     * Matched on the stored name, a UUID, so nothing else can match.
     */
    suspend fun delete(r: Recording): Unit = withContext(Dispatchers.Blocking) {
        val db = db.writableDatabase
        db.beginTransaction()
        try {
            db.delete("audio_files", LOOPER_COPY_WHERE, arrayOf(looperCopyPattern(r.storedName)))
            db.delete("recordings", "id = ?", arrayOf(r.id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        fileFor(r).delete()
    }

    /** Storage used, as the recorder shows it. Measured from the files, not the rows. */
    suspend fun storageBytes(): Long = withContext(Dispatchers.Blocking) {
        dir.list().sumOf { it.size() }
    }

    private fun update(id: Long, values: SqlValues) {
        values.put("updated_at", Clock.System.now().toEpochMilliseconds())
        db.writableDatabase.update("recordings", values, "id = ?", arrayOf(id.toString()))
    }

    private fun SqlCursor.toRecording() = Recording(
        id = getLong(0),
        songId = if (isNull(1)) null else getLong(1),
        sessionId = if (isNull(2)) null else getLong(2),
        name = requireString(3),
        storedName = requireString(4),
        durationMs = getLong(5),
        sizeBytes = getLong(6),
        sampleRate = getInt(7),
        latencyOffsetMs = getInt(8),
        alignMs = if (isNull(9)) null else getLong(9),
        source = TakeSource.entries.firstOrNull { it.name.equals(getString(10), ignoreCase = true) } ?: TakeSource.NONE,
        starred = getInt(11) == 1,
        rating = if (isNull(12)) null else getInt(12),
        notes = getString(13) ?: "",
        createdAt = Instant.fromEpochMilliseconds(getLong(14)),
    )

    companion object {
        private const val COLUMNS =
            "id, song_id, session_id, name, stored_name, duration_ms, size_bytes, sample_rate, " +
                "latency_offset_ms, align_ms, source, starred, rating, notes, created_at"

        /** How a looper entry made from a take is found. Shared with the test. */
        const val LOOPER_COPY_WHERE = "uri LIKE ?"
        fun looperCopyPattern(storedName: String) = "%/$storedName"
    }
}
