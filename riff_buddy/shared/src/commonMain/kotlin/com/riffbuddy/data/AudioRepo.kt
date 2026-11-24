package com.riffbuddy.data

import com.riffbuddy.domain.AudioFile
import com.riffbuddy.domain.SavedLoop
import com.riffbuddy.platform.Blocking
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Looper recordings and saved loops for both players.
 *
 * Rows only: the recordings themselves stay where the player keeps them. See
 * Schema's v3 migration for why they are references rather than copies.
 */
class AudioRepo(private val db: Db) {

    suspend fun all(): List<AudioFile> = withContext(Dispatchers.Blocking) {
        db.readableDatabase.rawQuery("SELECT $COLUMNS FROM audio_files ORDER BY updated_at DESC", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.toAudio()) } }
    }

    suspend fun byId(id: Long): AudioFile? = withContext(Dispatchers.Blocking) {
        db.readableDatabase.rawQuery("SELECT $COLUMNS FROM audio_files WHERE id = ?", arrayOf(id.toString()))
            .use { c -> if (c.moveToFirst()) c.toAudio() else null }
    }

    /** Returns the new row's id, or the existing one if this URI is already here. */
    suspend fun add(uri: String, originalName: String, sizeBytes: Long, songId: Long? = null): Long =
        withContext(Dispatchers.Blocking) {
            db.readableDatabase.rawQuery("SELECT id FROM audio_files WHERE uri = ? LIMIT 1", arrayOf(uri))
                .use { c -> if (c.moveToFirst()) return@withContext c.getLong(0) }
            val now = Clock.System.now().toEpochMilliseconds()
            db.writableDatabase.insertOrThrow(
                "audio_files", null,
                SqlValues().apply {
                    if (songId != null) put("song_id", songId) else putNull("song_id")
                    put("name", originalName.substringBeforeLast('.').ifBlank { originalName }.take(120))
                    put("original_name", originalName)
                    put("uri", uri)
                    put("size_bytes", sizeBytes)
                    put("created_at", now)
                    put("updated_at", now)
                },
            )
        }

    /** Learned on first decode: the header's duration is not always there or right. */
    suspend fun setDuration(id: Long, durationMs: Long): Unit = withContext(Dispatchers.Blocking) {
        db.writableDatabase.update(
            "audio_files", SqlValues().apply { put("duration_ms", durationMs) },
            "id = ?", arrayOf(id.toString()),
        )
    }

    /** "Find it again" after the file moved: same entry, new location. */
    suspend fun relink(id: Long, uri: String, sizeBytes: Long): Unit = withContext(Dispatchers.Blocking) {
        update(id, SqlValues().apply { put("uri", uri); put("size_bytes", sizeBytes) })
    }

    suspend fun rename(id: Long, name: String): Unit = withContext(Dispatchers.Blocking) {
        val clean = name.trim().take(120)
        if (clean.isNotEmpty()) update(id, SqlValues().apply { put("name", clean) })
    }

    suspend fun link(id: Long, songId: Long?): Unit = withContext(Dispatchers.Blocking) {
        update(id, SqlValues().apply { if (songId != null) put("song_id", songId) else putNull("song_id") })
    }

    suspend fun setBpm(id: Long, bpm: Int?): Unit = withContext(Dispatchers.Blocking) {
        update(id, SqlValues().apply { if (bpm != null) put("bpm", bpm) else putNull("bpm") })
    }

    suspend fun delete(id: Long): Unit = withContext(Dispatchers.Blocking) {
        db.writableDatabase.delete("audio_files", "id = ?", arrayOf(id.toString()))
    }

    /** What the looper remembers. Does not reorder the library. */
    suspend fun savePlayback(
        id: Long,
        positionMs: Long,
        speedPercent: Int,
        loopStartMs: Long?,
        loopEndMs: Long?,
        volumePercent: Int,
    ): Unit = withContext(Dispatchers.Blocking) {
        db.writableDatabase.update(
            "audio_files",
            SqlValues().apply {
                put("position_ms", positionMs.coerceAtLeast(0))
                put("speed_percent", speedPercent.coerceIn(25, 150))
                if (loopStartMs != null && loopEndMs != null) {
                    put("loop_start_ms", loopStartMs); put("loop_end_ms", loopEndMs)
                } else {
                    putNull("loop_start_ms"); putNull("loop_end_ms")
                }
                put("volume_percent", volumePercent.coerceIn(0, 100))
            },
            "id = ?", arrayOf(id.toString()),
        )
    }

    // ---- saved loops -------------------------------------------------------

    suspend fun loopsForAudio(audioId: Long): List<SavedLoop> = loops("audio_id", audioId)

    suspend fun loopsForTab(tabId: Long): List<SavedLoop> = loops("tab_id", tabId)

    private suspend fun loops(column: String, id: Long): List<SavedLoop> = withContext(Dispatchers.Blocking) {
        db.readableDatabase.rawQuery(
            "SELECT id, audio_id, tab_id, name, start_value, end_value, speed_percent FROM saved_loops " +
                "WHERE $column = ? ORDER BY start_value",
            arrayOf(id.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    SavedLoop(
                        id = c.getLong(0),
                        audioId = if (c.isNull(1)) null else c.getLong(1),
                        tabId = if (c.isNull(2)) null else c.getLong(2),
                        name = c.requireString(3),
                        start = c.getLong(4),
                        end = c.getLong(5),
                        speedPercent = c.getInt(6),
                    )
                )
            }
        }
    }

    suspend fun saveLoop(loop: SavedLoop): Long = withContext(Dispatchers.Blocking) {
        val now = Clock.System.now().toEpochMilliseconds()
        db.writableDatabase.insertOrThrow(
            "saved_loops", null,
            SqlValues().apply {
                if (loop.audioId != null) put("audio_id", loop.audioId) else putNull("audio_id")
                if (loop.tabId != null) put("tab_id", loop.tabId) else putNull("tab_id")
                put("name", loop.name.trim().ifBlank { loop.range }.take(80))
                put("start_value", loop.start)
                put("end_value", loop.end)
                put("speed_percent", loop.speedPercent)
                put("created_at", now)
                put("updated_at", now)
            },
        )
    }

    suspend fun deleteLoop(id: Long): Unit = withContext(Dispatchers.Blocking) {
        db.writableDatabase.delete("saved_loops", "id = ?", arrayOf(id.toString()))
    }

    private fun update(id: Long, values: SqlValues) {
        values.put("updated_at", Clock.System.now().toEpochMilliseconds())
        db.writableDatabase.update("audio_files", values, "id = ?", arrayOf(id.toString()))
    }

    private fun SqlCursor.toAudio() = AudioFile(
        id = getLong(0),
        songId = if (isNull(1)) null else getLong(1),
        name = requireString(2),
        originalName = requireString(3),
        uri = requireString(4),
        sizeBytes = getLong(5),
        durationMs = getLong(6),
        bpm = if (isNull(7)) null else getInt(7),
        positionMs = getLong(8),
        speedPercent = getInt(9),
        loopStartMs = if (isNull(10)) null else getLong(10),
        loopEndMs = if (isNull(11)) null else getLong(11),
        volumePercent = getInt(12),
        createdAt = Instant.fromEpochMilliseconds(getLong(13)),
        updatedAt = Instant.fromEpochMilliseconds(getLong(14)),
    )

    private companion object {
        const val COLUMNS =
            "id, song_id, name, original_name, uri, size_bytes, duration_ms, bpm, position_ms, " +
                "speed_percent, loop_start_ms, loop_end_ms, volume_percent, created_at, updated_at"
    }
}
