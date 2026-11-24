package com.riffbuddy.data

import com.riffbuddy.domain.PartStage
import com.riffbuddy.domain.SongPart
import com.riffbuddy.platform.Blocking
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The parts of each song and how far along they are. */
class PartRepo(private val db: Db) {

    suspend fun all(): List<SongPart> = withContext(Dispatchers.Blocking) {
        db.readableDatabase.rawQuery("SELECT $COLUMNS FROM song_parts ORDER BY song_id, position, id", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.toPart()) } }
    }

    suspend fun forSong(songId: Long): List<SongPart> = withContext(Dispatchers.Blocking) {
        db.readableDatabase.rawQuery(
            "SELECT $COLUMNS FROM song_parts WHERE song_id = ? ORDER BY position, id", arrayOf(songId.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(c.toPart()) } }
    }

    /** Adds [name] at the end of the song's list. */
    suspend fun add(songId: Long, name: String, now: Instant = Clock.System.now()): Long = withContext(Dispatchers.Blocking) {
        val next = db.readableDatabase.rawQuery(
            "SELECT COALESCE(MAX(position), -1) + 1 FROM song_parts WHERE song_id = ?", arrayOf(songId.toString()),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        db.writableDatabase.insertOrThrow("song_parts", null, SqlValues().apply {
            put("song_id", songId)
            put("name", name.trim().take(60))
            put("position", next)
            put("created_at", now.toEpochMilliseconds())
            put("updated_at", now.toEpochMilliseconds())
        })
    }

    suspend fun rename(id: Long, name: String): Unit = withContext(Dispatchers.Blocking) {
        val clean = name.trim().take(60)
        if (clean.isEmpty()) return@withContext
        update(id, SqlValues().apply { put("name", clean) })
    }

    /**
     * Moves a part to [stage]. Mastered is stamped the moment it's reached and
     * cleared if the part goes back, so "parts mastered over time" is a real
     * history rather than a guess.
     */
    suspend fun setStage(id: Long, stage: PartStage, now: Instant = Clock.System.now()): Unit = withContext(Dispatchers.Blocking) {
        update(id, SqlValues().apply {
            put("stage", stage.name)
            put("stage_changed_at", now.toEpochMilliseconds())
            if (stage == PartStage.MASTERED) put("mastered_at", now.toEpochMilliseconds()) else putNull("mastered_at")
        })
    }

    /** Writes the song's parts back in the order given. */
    suspend fun reorder(ids: List<Long>): Unit = withContext(Dispatchers.Blocking) {
        val db = db.writableDatabase
        db.beginTransaction()
        try {
            ids.forEachIndexed { i, id -> db.execSQL("UPDATE song_parts SET position = ? WHERE id = ?", arrayOf<Any>(i, id)) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    suspend fun delete(id: Long): Unit = withContext(Dispatchers.Blocking) {
        db.writableDatabase.delete("song_parts", "id = ?", arrayOf(id.toString()))
    }

    private fun update(id: Long, values: SqlValues) {
        values.put("updated_at", Clock.System.now().toEpochMilliseconds())
        db.writableDatabase.update("song_parts", values, "id = ?", arrayOf(id.toString()))
    }

    private fun SqlCursor.toPart() = SongPart(
        id = getLong(0),
        songId = getLong(1),
        name = requireString(2),
        position = getInt(3),
        stage = PartStage.fromName(getString(4)),
        stageChangedAt = if (isNull(5)) null else Instant.fromEpochMilliseconds(getLong(5)),
        masteredAt = if (isNull(6)) null else Instant.fromEpochMilliseconds(getLong(6)),
        createdAt = Instant.fromEpochMilliseconds(getLong(7)),
    )

    private companion object {
        const val COLUMNS = "id, song_id, name, position, stage, stage_changed_at, mastered_at, created_at"
    }
}

/** Which milestones have been earned, and whether each has been celebrated yet. */
class MilestoneRepo(private val db: Db) {

    /** Key to celebrated. */
    suspend fun earned(): Map<String, Boolean> = withContext(Dispatchers.Blocking) {
        db.readableDatabase.rawQuery("SELECT key, celebrated FROM milestones", null)
            .use { c -> buildMap { while (c.moveToNext()) put(c.requireString(0), c.getInt(1) == 1) } }
    }

    /** Records [keys] as earned. [celebrated] true records them silently. */
    suspend fun earn(keys: Collection<String>, celebrated: Boolean, now: Instant = Clock.System.now()): Unit =
        withContext(Dispatchers.Blocking) {
            val db = db.writableDatabase
            db.beginTransaction()
            try {
                keys.forEach { key ->
                    db.execSQL(
                        "INSERT OR IGNORE INTO milestones (key, earned_at, celebrated) VALUES (?, ?, ?)",
                        arrayOf<Any>(key, now.toEpochMilliseconds(), if (celebrated) 1 else 0),
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

    suspend fun markCelebrated(key: String): Unit = withContext(Dispatchers.Blocking) {
        db.writableDatabase.execSQL("UPDATE milestones SET celebrated = 1 WHERE key = ?", arrayOf<Any>(key))
    }

    suspend fun earnedAt(): Map<String, Instant> = withContext(Dispatchers.Blocking) {
        db.readableDatabase.rawQuery("SELECT key, earned_at FROM milestones", null)
            .use { c -> buildMap { while (c.moveToNext()) put(c.requireString(0), Instant.fromEpochMilliseconds(c.getLong(1))) } }
    }
}
