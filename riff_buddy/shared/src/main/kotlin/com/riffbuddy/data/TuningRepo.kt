package com.riffbuddy.data

import com.riffbuddy.domain.music.Tuning
import com.riffbuddy.domain.music.TuningGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The tuning library: the built-ins plus the player's own.
 *
 * Built-ins live in code ([Tuning.BUILT_IN], tested against interval math)
 * and are copied into the table so that a song can point at one by id, the
 * same way it points at a custom tuning. Copied in list order, by name, and
 * never updated — so two installs number them alike, and a backup's song
 * links land on the same tunings when restored.
 */
class TuningRepo(private val db: Db) {

    /** Adds any built-in not yet in the table. Cheap to repeat. */
    suspend fun seed(): Unit = withContext(Dispatchers.IO) {
        val d = db.writableDatabase
        d.beginTransaction()
        try {
            Tuning.BUILT_IN.forEach { t ->
                d.insertWithOnConflict("tunings", null, t.toValues(), Conflict.IGNORE)
            }
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
    }

    /** Built-ins in their listed order, then custom tunings by name. */
    suspend fun all(): List<Tuning> = withContext(Dispatchers.IO) {
        val rows = db.readableDatabase.rawQuery(
            "SELECT id, name, grp, strings, is_custom FROM tunings", null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val midi = Tuning.parseStrings(c.requireString(3)) ?: continue
                    add(
                        Tuning(
                            id = c.getLong(0),
                            name = c.requireString(1),
                            group = runCatching { TuningGroup.valueOf(c.requireString(2)) }.getOrDefault(TuningGroup.CUSTOM),
                            midi = midi,
                            isCustom = c.getInt(4) != 0,
                        )
                    )
                }
            }
        }
        val order = Tuning.BUILT_IN.mapIndexed { i, t -> t.name to i }.toMap()
        rows.filter { !it.isCustom }.sortedBy { order[it.name] ?: Int.MAX_VALUE } +
            rows.filter { it.isCustom }.sortedBy { it.name.lowercase() }
    }

    /**
     * Saves a custom tuning, new (id 0) or edited. Returns its id, or null if
     * the name is already taken — names are unique, and a second "My drop"
     * would be two chips nobody could tell apart.
     */
    suspend fun saveCustom(tuning: Tuning): Long? = withContext(Dispatchers.IO) {
        val values = tuning.copy(group = TuningGroup.CUSTOM, isCustom = true).toValues()
        val d = db.writableDatabase
        if (tuning.id == 0L) {
            d.insertWithOnConflict("tunings", null, values, Conflict.IGNORE).takeIf { it > 0 }
        } else {
            val taken = d.rawQuery(
                "SELECT id FROM tunings WHERE name = ? AND id != ?", arrayOf(tuning.name, tuning.id.toString()),
            ).use { it.moveToFirst() }
            if (taken) null
            else {
                d.update("tunings", values, "id = ? AND is_custom = 1", arrayOf(tuning.id.toString()))
                tuning.id
            }
        }
    }

    /** Custom only. Songs in it keep their other details; their tuning becomes unset (SET NULL). */
    suspend fun deleteCustom(id: Long): Unit = withContext(Dispatchers.IO) {
        db.writableDatabase.delete("tunings", "id = ? AND is_custom = 1", arrayOf(id.toString()))
    }

    private fun Tuning.toValues() = SqlValues().apply {
        put("name", name)
        put("grp", group.name)
        put("strings", storedStrings())
        put("string_count", stringCount)
        put("is_custom", if (isCustom) 1 else 0)
    }
}
