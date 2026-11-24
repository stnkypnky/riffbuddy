package com.riffbuddy.data

import com.riffbuddy.audio.Accent
import com.riffbuddy.audio.ClickSound
import com.riffbuddy.audio.MetronomeSettings
import com.riffbuddy.audio.Subdivision
import com.riffbuddy.audio.TimeSignature
import com.riffbuddy.platform.Blocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A saved metronome setup. */
data class MetronomePreset(val id: Long, val name: String, val settings: MetronomeSettings)

/**
 * Presets, stored in the `metronome_presets` table.
 *
 * The accent list is the only field that is not already a scalar, and it is kept
 * as initials — "A,N,N,M" — rather than JSON. It is a handful of characters, it
 * is readable straight out of a database browser, and what matters is that a
 * preset round-trips exactly, which [PresetRepo.parseAccents]
 * and its test cover.
 */
class PresetRepo(private val db: Db) {

    suspend fun all(): List<MetronomePreset> = withContext(Dispatchers.Blocking) {
        db.readableDatabase.rawQuery(
            "SELECT id, name, bpm, time_sig, accents, subdivision, sound FROM metronome_presets ORDER BY name",
            null,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        MetronomePreset(
                            id = c.getLong(0),
                            name = c.requireString(1),
                            settings = MetronomeSettings(
                                bpm = c.getInt(2),
                                timeSignature = parseTimeSignature(c.getString(3)),
                                accents = parseAccents(c.getString(4)),
                                subdivision = Subdivision.entries.firstOrNull { it.name == c.getString(5) }
                                    ?: Subdivision.NONE,
                                sound = ClickSound.entries.firstOrNull { it.name == c.getString(6) }
                                    ?: ClickSound.CLICK,
                            ),
                        )
                    )
                }
            }
        }
    }

    suspend fun save(name: String, settings: MetronomeSettings): Long = withContext(Dispatchers.Blocking) {
        val values = SqlValues().apply {
            put("name", name)
            put("bpm", settings.bpm)
            put("time_sig", settings.timeSignature.toString())
            put("accents", formatAccents(settings))
            put("subdivision", settings.subdivision.name)
            put("sound", settings.sound.name)
        }
        db.writableDatabase.insert("metronome_presets", null, values)
    }

    suspend fun delete(id: Long): Unit = withContext(Dispatchers.Blocking) {
        db.writableDatabase.delete("metronome_presets", "id = ?", arrayOf(id.toString()))
    }

    companion object {
        /**
         * Accents as initials, one per beat: "A,N,N,M".
         *
         * Written from [MetronomeSettings.accentFor] rather than from the raw
         * list, so a preset saved before any accent was edited still records the
         * accented downbeat it was actually playing — an empty list means
         * "defaults", and defaults are not the same as all-normal.
         */
        fun formatAccents(settings: MetronomeSettings): String =
            (0 until settings.beatsPerBar)
                .joinToString(",") { settings.accentFor(it).name.first().toString() }

        fun parseAccents(raw: String?): List<Accent> {
            if (raw.isNullOrBlank()) return emptyList()
            return raw.split(",").mapNotNull { token ->
                when (token.trim().uppercase()) {
                    "A" -> Accent.ACCENT
                    "N" -> Accent.NORMAL
                    "M" -> Accent.MUTED
                    else -> null
                }
            }
        }

        /** Tolerant of anything odd in the column: falls back to 4/4 rather than throwing. */
        fun parseTimeSignature(raw: String?): TimeSignature {
            val parts = raw?.split("/") ?: return TimeSignature.FOUR_FOUR
            val beats = parts.getOrNull(0)?.toIntOrNull() ?: return TimeSignature.FOUR_FOUR
            val unit = parts.getOrNull(1)?.toIntOrNull() ?: return TimeSignature.FOUR_FOUR
            return runCatching { TimeSignature(beats, unit) }.getOrDefault(TimeSignature.FOUR_FOUR)
        }
    }
}
