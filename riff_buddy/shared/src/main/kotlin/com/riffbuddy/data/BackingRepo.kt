package com.riffbuddy.data

import com.riffbuddy.domain.backing.BackingSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/** A saved backing track: a recipe, linked to a song or not. */
data class SavedBacking(val id: Long, val songId: Long?, val name: String, val spec: BackingSpec)

class BackingRepo(private val db: Db) {

    suspend fun all(): List<SavedBacking> = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery(
            "SELECT id, song_id, name, spec_json FROM backing_tracks ORDER BY updated_at DESC", null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val spec = runCatching { BackingSpec.fromJson(c.requireString(3)) }.getOrNull() ?: continue
                    add(SavedBacking(c.getLong(0), if (c.isNull(1)) null else c.getLong(1), c.requireString(2), spec))
                }
            }
        }
    }

    /** Saves a new track, or overwrites [id]'s recipe. Returns the id. */
    suspend fun save(spec: BackingSpec, songId: Long?, id: Long? = null): Long = withContext(Dispatchers.IO) {
        val now = Instant.now().toEpochMilli()
        val values = SqlValues().apply {
            if (songId != null) put("song_id", songId) else putNull("song_id")
            put("name", spec.name.ifBlank { "Backing track" }.take(120))
            put("spec_json", spec.toJson())
            put("updated_at", now)
        }
        if (id != null) {
            db.writableDatabase.update("backing_tracks", values, "id = ?", arrayOf(id.toString()))
            id
        } else {
            values.put("created_at", now)
            db.writableDatabase.insertOrThrow("backing_tracks", null, values)
        }
    }

    suspend fun delete(id: Long): Unit = withContext(Dispatchers.IO) {
        db.writableDatabase.delete("backing_tracks", "id = ?", arrayOf(id.toString()))
    }
}
