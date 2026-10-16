package com.riffbuddy.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * What a backup file is.
 *
 * The rules here are pure so they can be tested without a database, and they
 * are the rules that matter: a backup that silently imports the wrong thing is
 * worse than one that refuses, because the refusal is visible and the silent
 * wrong import is discovered weeks later when the practice log is nonsense.
 */
object BackupFormat {

    const val MAGIC = "riffbuddy-backup"
    /**
     * 2 since the tab library: the zip gained a `tabs/` folder holding
     * the files the `tab_files` rows describe. A version 1 backup still restores
     * — it simply has no tab files in it. A version 1 *app* refuses a version 2
     * backup, which is right: it would restore the rows and drop the files.
     */
    const val VERSION = 2

    /** Where a tab file's bytes live inside the backup zip. */
    const val TABS_FOLDER = "tabs/"

    /** Where a take's WAV lives inside the backup zip; takes are part of every export. */
    const val RECORDINGS_FOLDER = "recordings/"

    fun tabEntryName(storedName: String): String = "$TABS_FOLDER$storedName"

    /**
     * The tables whose rows name files that travel with them: table, and the
     * folder the files use both in app storage and inside the zip.
     */
    val FILE_TABLES = listOf("tab_files" to TABS_FOLDER, "recordings" to RECORDINGS_FOLDER)

    /**
     * Whether a name from a backup is safe to write into the tab folder.
     *
     * A backup is a file from outside the app, and the names inside it become
     * file paths on restore. Without this, a damaged or hostile backup naming
     * an entry `tabs/../databases/riffbuddy.db` would write over the database —
     * the "zip slip" that has bitten real Android apps. Only names of the shape
     * the app itself creates (a UUID and a short extension) are accepted, so
     * there is no path syntax to get wrong.
     */
    fun isSafeStoredName(name: String): Boolean = SAFE_STORED_NAME.matches(name)

    private val SAFE_STORED_NAME =
        Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.[a-z0-9]{1,8}$")
    const val ENTRY_NAME = "riffbuddy.json"
    const val FILE_EXTENSION = "riffbuddy"

    /**
     * Tables that go into a backup, in an order that respects foreign keys —
     * a song must exist before a session item can point at it, and a song
     * part before a session item tagged with it. `BackupOrderTest` checks this
     * against the schema's own foreign keys.
     *
     * `ai_cache` is deliberately absent: it is a cache, restoring it would
     * serve stale answers, and it is the largest thing in the database.
     *
     * [DELETIONS] comes first: a merge reads what has been deleted before
     * it looks at a single row, so a deleted item is not added back. In a
     * full replace it is the last table emptied, after every other table's
     * delete has noted itself there, so a replace leaves only the backup's
     * own notes.
     */
    val TABLES = listOf(
        DELETIONS,
        "tunings", "songs", "song_parts", "sessions", "session_items",
        "guitars", "amps", "pedals", "rigs",
        "tone_cards", "ai_messages", "metronome_presets", "settings", "ai_usage",
        "tab_files", "audio_files", "saved_loops", "recordings", "backing_tracks",
        "lessons", "homework", "routines", "routine_steps",
        "milestones", "custom_shapes",
    )

    /**
     * Tables that are in the database and deliberately not in a backup.
     *
     * Written down so a test can hold the two lists against the schema: every
     * table is in exactly one of them. Without that, the next table added would
     * be left out of backups by default and nobody would know until a restore
     * came back without it.
     */
    val EXCLUDED = setOf("ai_cache")

    /** Notes of deleted items, so a deletion reaches every device; not something to count for a person. */
    const val DELETIONS = "deletions"

    fun manifest(appVersion: String, now: Instant = Instant.now()): JSONObject = JSONObject()
        .put("format", MAGIC)
        .put("schema_version", VERSION)
        .put("app_version", appVersion)
        .put("created_at", now.toEpochMilli())

    /** Why a file cannot be restored, phrased for a person. */
    sealed interface Problem {
        data object NotABackup : Problem
        data class TooNew(val fileVersion: Int) : Problem
        data class Damaged(val detail: String) : Problem

        val message: String
            get() = when (this) {
                NotABackup -> "That is not a Riff Buddy backup file."
                is TooNew ->
                    "That backup was made by a newer version of Riff Buddy (format " +
                        "$fileVersion, this one reads $VERSION). Update the app and try again."
                is Damaged -> "That backup file is damaged and cannot be read. $detail"
            }
    }

    /**
     * Checks a file's manifest before a single row is touched.
     *
     * The "too new" case matters more than it looks. A newer format may hold
     * fields this version would silently drop, and a restore that quietly loses
     * data is the worst possible outcome for a feature whose entire job is not
     * losing data.
     */
    fun validate(root: JSONObject): Problem? {
        if (root.optString("format") != MAGIC) return Problem.NotABackup
        val version = root.optInt("schema_version", -1)
        if (version <= 0) return Problem.Damaged("No format version in it.")
        if (version > VERSION) return Problem.TooNew(version)
        if (!root.has("tables")) return Problem.Damaged("It contains no data.")
        return null
    }

    /** What a backup holds, for the summary shown before it is applied. */
    fun summarize(root: JSONObject): Map<String, Int> {
        val tables = root.optJSONObject("tables") ?: return emptyMap()
        return (TABLES - DELETIONS).mapNotNull { name ->
            val rows = tables.optJSONArray(name) ?: return@mapNotNull null
            if (rows.length() == 0) null else name to rows.length()
        }.toMap()
    }

    fun createdAt(root: JSONObject): Instant? =
        root.optLong("created_at", 0L).takeIf { it > 0 }?.let(Instant::ofEpochMilli)

    /**
     * Keys are excluded by construction, not by filtering.
     *
     * They live in the platform's secure key store and never enter the database, so
     * there is nothing here to strip. This function exists so that a future
     * change which put a key in a table would fail a test rather than ship a
     * backup with a secret in it.
     */
    fun containsSecrets(root: JSONObject): Boolean {
        val forbidden = listOf("api_key", "apikey", "secret", "token", "password")
        val text = root.toString().lowercase()
        return forbidden.any { text.contains("\"$it\"") }
    }

    /** A filename a person can recognize among a hundred downloads. */
    fun suggestedFileName(now: Instant = Instant.now()): String {
        val date = now.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        return "riff-buddy-$date.$FILE_EXTENSION"
    }

    /** How stale a backup is allowed to get before Home mentions it. */
    const val REMINDER_AFTER_DAYS = 30L

    fun needsReminder(lastBackup: Instant?, now: Instant = Instant.now()): Boolean {
        if (lastBackup == null) return false
        return java.time.Duration.between(lastBackup, now).toDays() >= REMINDER_AFTER_DAYS
    }

    /** Merge or replace — both are offered, and they mean very different things. */
    enum class Mode(val label: String, val explanation: String) {
        MERGE(
            "Add to what is here",
            "Keeps what you have, adds what's new from the backup (both devices' additions are " +
                "kept), and takes the backup's version of anything edited more recently there.",
        ),
        REPLACE(
            "Replace all",
            "Wipes what is here and makes this device an exact copy of the backup. Use it on a " +
                "fresh install, or when the other device is where you keep your practice.",
        ),
    }


}
