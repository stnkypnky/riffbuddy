package com.riffbuddy.data

import com.riffbuddy.platform.Blocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The settings door into the database, and the pattern every repository follows.
 *
 * Two rules it exists to keep:
 *
 * 1. No screen ever sees a cursor. Each feature has its own repository, and
 *    each one hands back plain data classes.
 *
 * 2. No query runs on the main thread. Every method suspends onto Dispatchers.Blocking.
 *    SQLite is fast, but "fast" on a 500-song search is still tens of
 *    milliseconds, and the frame budget is 16. Compose calls these from a
 *    coroutine, so the suspend keyword is the whole cost of getting it right.
 */
class Repo(private val db: Db) {

    /** A stored preference. Secrets are not here; they are in secure storage. */
    suspend fun setting(key: String, default: String? = null): String? = withContext(Dispatchers.Blocking) {
        db.readableDatabase.rawQuery(
            "SELECT value FROM settings WHERE key = ?",
            arrayOf(key),
        ).use { c -> if (c.moveToFirst()) c.getString(0) else default }
    }

    /**
     * Insert-or-replace, written the long way round on purpose.
     *
     * The obvious spelling is `INSERT … ON CONFLICT … DO UPDATE`, and it is what
     * was here. UPSERT arrived in SQLite 3.24, which is Android 11 / API 30.
     * minSdk is 26, and an API 28 tablet has SQLite 3.22 — so on such a device
     * this threw `near "ON": syntax error` and took the app down with it.
     * `insertWithOnConflict` is the framework API that works everywhere, and
     * CONFLICT_REPLACE is exact here because `key` is the whole primary key.
     */
    suspend fun putSetting(key: String, value: String): Unit = withContext(Dispatchers.Blocking) {
        db.writableDatabase.insertWithOnConflict(
            "settings", null,
            SqlValues().apply {
                put("key", key)
                put("value", value)
            },
            Conflict.REPLACE,
        )
    }

    /**
     * Row counts per table, for the storage line on the Me tab.
     *
     * This is not idle curiosity: an export, a wipe and an import must
     * restore every entity with *identical counts*, and this is the thing that
     * will be read to check it.
     */
    suspend fun tableCounts(): Map<String, Int> = withContext(Dispatchers.Blocking) {
        val d = db.readableDatabase
        val tables = d.rawQuery(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
            null,
        ).use { c -> buildList { while (c.moveToNext()) add(c.requireString(0)) } }

        tables.associateWith { table ->
            // Table names come from sqlite_master, not from anything a user typed,
            // so interpolating here cannot smuggle in SQL. A bound parameter
            // cannot name a table in any case.
            d.rawQuery("SELECT COUNT(*) FROM \"$table\"", null)
                .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        }
    }

    /** Schema version actually on disk, as opposed to the one the code expects. */
    suspend fun schemaVersion(): Int = withContext(Dispatchers.Blocking) {
        db.readableDatabase.version
    }

    companion object {
        /** When the backup export last ran, epoch millis. Drives the 30-day reminder. */
        const val LAST_BACKUP_AT = "last_backup_at"

        /** The calibrated recording latency, and how well it held. */
        const val LATENCY_OFFSET_MS = "latency_offset_ms"
        const val LATENCY_SPREAD_MS = "latency_spread_ms"
        const val LATENCY_CALIBRATED_AT = "latency_calibrated_at"

        /** The practice reminder, and when the backup nudge was last shown. */
        const val REMINDER_ENABLED = "reminder_enabled"
        const val REMINDER_TIME = "reminder_time"
        const val REMINDER_DAYS = "reminder_days"
        const val REMINDER_SKIP_PRACTICED = "reminder_skip_practiced"
        const val BACKUP_NUDGED_AT = "backup_nudged_at"

        /** Recorder input gain, dB — kept apart for the device mic and the amp's own output. */
        const val RECORDER_GAIN_MIC = "recorder_gain_db_mic"
        /**
         * The amp's own output. A new key rather than reusing
         * the USB one: a gain set for the USB input would be wildly wrong for
         * a headset microphone input.
         */
        const val RECORDER_GAIN_LINE = "recorder_gain_db_line"

        /** Seconds of countdown before a take starts; 0 or absent is none. */
        const val RECORDER_COUNTDOWN = "recorder_countdown_s"

        /** The coach's answer length (AskPrompt.Length name). */
        const val COACH_LENGTH = "coach_answer_length"

        /** Whether Ask searches the web, when a model that can is available. */
        const val COACH_WEB = "coach_web_search"

        /** The color scheme: a Finish id. Absent is the default finish. */
        const val FINISH = "appearance_finish"

        /** Which provider the coach tries first. */
        const val AI_PRIMARY_PROVIDER = "ai_primary_provider"

        /** "true" lets a paid provider be asked when the main one can't answer. Absent is off. */
        const val AI_PAID_FALLBACK = "ai_paid_fallback"

        /**
         * The model picked for a provider.
         *
         * Per provider, because the choice is per provider: someone using, say,
         * gemma-4-26b on Gemini and gpt-oss-120b on Groq would lose one of them
         * to a single key.
         */
        fun aiModelKey(provider: String): String = "ai_model_${provider.lowercase()}"
    }
}
