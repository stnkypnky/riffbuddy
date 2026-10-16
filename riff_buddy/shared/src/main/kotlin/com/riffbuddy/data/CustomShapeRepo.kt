package com.riffbuddy.data

import com.riffbuddy.domain.music.CustomShape
import com.riffbuddy.domain.music.CustomShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/** The player's own scales and dot shapes, newest edit first. */
class CustomShapeRepo(private val db: Db) {

    suspend fun all(): List<CustomShape> = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery(
            "SELECT id, name, kind, root, notes, tuning FROM custom_shapes ORDER BY updated_at DESC", null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    // A kind this version doesn't know (from a newer backup) is skipped, not guessed at.
                    val kind = runCatching { CustomShape.Kind.valueOf(c.requireString(2)) }.getOrNull() ?: continue
                    val notes = c.requireString(4)
                    add(
                        CustomShape(
                            id = c.getLong(0),
                            name = c.requireString(1),
                            kind = kind,
                            root = c.getInt(3).mod(12),
                            pitchClasses = if (kind == CustomShape.Kind.SCALE) CustomShapes.decodeScale(notes) else emptySet(),
                            cells = if (kind == CustomShape.Kind.DOTS) CustomShapes.decodeDots(notes) else emptySet(),
                            tuningName = if (c.isNull(5)) null else c.requireString(5),
                        )
                    )
                }
            }
        }
    }

    /** Saves a new shape (id 0) or overwrites one. Returns the id. */
    suspend fun save(shape: CustomShape): Long = withContext(Dispatchers.IO) {
        val now = Instant.now().toEpochMilli()
        val values = SqlValues().apply {
            put("name", shape.name.trim().ifBlank { "My shape" }.take(80))
            put("kind", shape.kind.name)
            put("root", shape.root.mod(12).toLong())
            put("notes", CustomShapes.encode(shape))
            if (shape.tuningName != null) put("tuning", shape.tuningName) else putNull("tuning")
            put("updated_at", now)
        }
        if (shape.id != 0L) {
            db.writableDatabase.update("custom_shapes", values, "id = ?", arrayOf(shape.id.toString()))
            shape.id
        } else {
            values.put("created_at", now)
            db.writableDatabase.insertOrThrow("custom_shapes", null, values)
        }
    }

    suspend fun delete(id: Long): Unit = withContext(Dispatchers.IO) {
        db.writableDatabase.delete("custom_shapes", "id = ?", arrayOf(id.toString()))
    }
}
