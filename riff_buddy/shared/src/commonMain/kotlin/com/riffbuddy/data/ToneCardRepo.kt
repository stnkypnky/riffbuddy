package com.riffbuddy.data

import com.riffbuddy.ai.GeneratedCard
import com.riffbuddy.ai.ProviderId
import com.riffbuddy.platform.Blocking
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A tone card as stored, with its provenance: which provider and model made it. */
data class SavedToneCard(
    val id: Long,
    val songId: Long,
    val part: String,
    val version: Int,
    val isStarred: Boolean,
    val provider: ProviderId,
    val model: String,
    val promptVersion: String,
    val responseMs: Long,
    val contentJson: String,
    val feedback: String,
    val useful: Boolean?,
    val createdAt: Instant,
)

/**
 * Tone cards kept against their song.
 *
 * Versions are numbered per song *and part*, because a rhythm tone and a solo
 * tone for the same song are two conversations, not two attempts at one. Without
 * that, refining the solo would look like the fourth revision of the rhythm.
 */
class ToneCardRepo(private val db: Db) {

    suspend fun save(
        songId: Long,
        rigId: Long?,
        part: String,
        generated: GeneratedCard,
        feedback: String = "",
    ): Long = withContext(Dispatchers.Blocking) {
        val nextVersion = db.readableDatabase.rawQuery(
            "SELECT COALESCE(MAX(version), 0) + 1 FROM tone_cards WHERE song_id = ? AND part = ?",
            arrayOf(songId.toString(), part),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 1 }

        val values = SqlValues().apply {
            put("song_id", songId)
            if (rigId != null) put("rig_id", rigId) else putNull("rig_id")
            put("part", part)
            put("version", nextVersion)
            // The first card for a part is starred by default: one card is the
            // best card, and making the player star it to make it the song's tone
            // would be ceremony for its own sake.
            put("is_starred", if (nextVersion == 1) 1 else 0)
            put("prompt_version", generated.promptVersion)
            put("provider", generated.provider.name)
            put("model", generated.model)
            put("response_ms", generated.elapsedMs)
            put("content_json", generated.rawJson)
            put("feedback", feedback)
            put("created_at", Clock.System.now().toEpochMilliseconds())
        }
        db.writableDatabase.insert("tone_cards", null, values)
    }

    suspend fun forSong(songId: Long): List<SavedToneCard> = withContext(Dispatchers.Blocking) {
        db.readableDatabase.rawQuery(
            "SELECT id, song_id, part, version, is_starred, provider, model, prompt_version, " +
                "response_ms, content_json, feedback, useful, created_at " +
                "FROM tone_cards WHERE song_id = ? AND archived = 0 " +
                "ORDER BY part ASC, version DESC",
            arrayOf(songId.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        SavedToneCard(
                            id = c.getLong(0),
                            songId = c.getLong(1),
                            part = c.requireString(2),
                            version = c.getInt(3),
                            isStarred = c.getInt(4) == 1,
                            provider = ProviderId.fromName(c.getString(5)),
                            model = c.requireString(6),
                            promptVersion = c.requireString(7),
                            responseMs = if (c.isNull(8)) 0 else c.getLong(8),
                            contentJson = c.requireString(9),
                            feedback = c.getString(10) ?: "",
                            useful = if (c.isNull(11)) null else c.getInt(11) == 1,
                            createdAt = Instant.fromEpochMilliseconds(c.getLong(12)),
                        )
                    )
                }
            }
        }
    }

    /**
     * Starring makes a version the song's tone for that part.
     *
     * Scoped to the part, so starring a solo tone does not un-star the rhythm
     * one — they are answers to different questions and both can be the best.
     */
    suspend fun star(cardId: Long): Unit = withContext(Dispatchers.Blocking) {
        val db = db.writableDatabase
        db.rawQuery(
            "SELECT song_id, part FROM tone_cards WHERE id = ?", arrayOf(cardId.toString()),
        ).use { c ->
            if (!c.moveToFirst()) return@withContext
            val songId = c.getLong(0)
            val part = c.getString(1)
            db.execSQL(
                "UPDATE tone_cards SET is_starred = 0 WHERE song_id = ? AND part = ?",
                arrayOf<Any?>(songId, part),
            )
        }
        db.execSQL("UPDATE tone_cards SET is_starred = 1 WHERE id = ?", arrayOf<Any?>(cardId))
    }

    /** Thumbs up or down: was the card close enough to start playing? */
    suspend fun rate(cardId: Long, useful: Boolean): Unit = withContext(Dispatchers.Blocking) {
        db.writableDatabase.execSQL(
            "UPDATE tone_cards SET useful = ? WHERE id = ?",
            arrayOf<Any?>(if (useful) 1 else 0, cardId),
        )
    }

    suspend fun delete(cardId: Long): Unit = withContext(Dispatchers.Blocking) {
        db.writableDatabase.delete("tone_cards", "id = ?", arrayOf(cardId.toString()))
    }
}
