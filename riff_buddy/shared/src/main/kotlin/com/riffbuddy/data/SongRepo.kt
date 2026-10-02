package com.riffbuddy.data

import com.riffbuddy.domain.Song
import com.riffbuddy.domain.SongStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Songs in the `songs` table.
 *
 * Reads the whole songbook and lets `Songbook` filter it in Kotlin. For the 500
 * songs of a large songbook that is a fraction of a millisecond, and it keeps the
 * matching rules — the ones about apostrophes and case — in one tested place
 * instead of split between here and a LIKE clause.
 */
class SongRepo(private val db: Db) {

    suspend fun all(): List<Song> = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery("SELECT $COLUMNS FROM songs", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.toSong()) } }
    }

    suspend fun byId(id: Long): Song? = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery("SELECT $COLUMNS FROM songs WHERE id = ?", arrayOf(id.toString()))
            .use { c -> if (c.moveToFirst()) c.toSong() else null }
    }

    suspend fun save(song: Song, now: Instant = Instant.now()): Long = withContext(Dispatchers.IO) {
        val values = song.toValues(now)
        if (song.id == 0L) {
            values.put("created_at", now.toEpochMilli())
            db.writableDatabase.insert("songs", null, values)
        } else {
            db.writableDatabase.update("songs", values, "id = ?", arrayOf(song.id.toString()))
            song.id
        }
    }

    /**
     * Deletes the song. Its session items survive with a null song id, so the
     * practice log keeps the minutes — deleting a song should lose the song, not
     * rewrite history. The schema's ON DELETE SET NULL does the work.
     */
    suspend fun delete(id: Long): Unit = withContext(Dispatchers.IO) {
        db.writableDatabase.delete("songs", "id = ?", arrayOf(id.toString()))
    }

    suspend fun setSongsterrUrl(id: Long, url: String?): Unit = withContext(Dispatchers.IO) {
        db.writableDatabase.execSQL(
            "UPDATE songs SET songsterr_url = ?, updated_at = ? WHERE id = ?",
            arrayOf<Any?>(url, Instant.now().toEpochMilli(), id),
        )
    }

    private fun Song.toValues(now: Instant) = SqlValues().apply {
        put("title", title.trim())
        put("artist", artist.trim())
        put("status", status.name)
        if (tuningId != null) put("tuning_id", tuningId) else putNull("tuning_id")
        put("capo", capo)
        put("song_key", key)
        put("time_sig", timeSignature)
        if (targetBpm != null) put("target_bpm", targetBpm) else putNull("target_bpm")
        if (currentBpm != null) put("current_bpm", currentBpm) else putNull("current_bpm")
        if (selfDifficulty != null) put("self_difficulty", selfDifficulty) else putNull("self_difficulty")
        put("tags", tags.trim())
        put("notes", notes)
        put("songsterr_url", songsterrUrl)
        // Stamped the first time the song is Mastered and kept while it stays
        // so; cleared if it goes back to being worked on.
        if (status == SongStatus.MASTERED) put("mastered_at", (masteredAt ?: now).toEpochMilli())
        else putNull("mastered_at")
        put("updated_at", now.toEpochMilli())
    }

    private fun SqlCursor.toSong() = Song(
        id = getLong(0),
        title = requireString(1),
        artist = requireString(2),
        status = SongStatus.fromName(getString(3)),
        tuningId = if (isNull(4)) null else getLong(4),
        capo = getInt(5),
        key = getString(6),
        timeSignature = getString(7),
        targetBpm = if (isNull(8)) null else getInt(8),
        currentBpm = if (isNull(9)) null else getInt(9),
        selfDifficulty = if (isNull(10)) null else getInt(10),
        tags = getString(11) ?: "",
        notes = getString(12) ?: "",
        songsterrUrl = getString(13),
        createdAt = Instant.ofEpochMilli(getLong(14)),
        updatedAt = Instant.ofEpochMilli(getLong(15)),
        masteredAt = if (isNull(16)) null else Instant.ofEpochMilli(getLong(16)),
    )

    private companion object {
        const val COLUMNS =
            "id, title, artist, status, tuning_id, capo, song_key, time_sig, " +
                "target_bpm, current_bpm, self_difficulty, tags, notes, songsterr_url, " +
                "created_at, updated_at, mastered_at"
    }
}
