package com.riffbuddy.data

import com.riffbuddy.domain.Session
import com.riffbuddy.domain.SessionItem
import com.riffbuddy.domain.SessionMode
import com.riffbuddy.platform.Blocking
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Practice sessions and their items.
 *
 * Sessions are written the moment they start, not when they end. That is what
 * makes recovery possible: if the app is killed mid-practice, the row is already
 * there with no end time, and [openSession] finds it on the next launch. Holding
 * it in memory until "End session" would lose exactly the sessions worth keeping
 * — the long ones.
 */
class SessionRepo(private val db: Db) {

    suspend fun start(mode: SessionMode, now: Instant = Clock.System.now()): Long = withContext(Dispatchers.Blocking) {
        val values = SqlValues().apply {
            put("started_at", now.toEpochMilliseconds())
            put("mode", mode.name.lowercase())
            put("duration_sec", 0)
            put("updated_at", now.toEpochMilliseconds())
        }
        db.writableDatabase.insert("sessions", null, values)
    }

    /** The session left running by a previous launch, if any. */
    suspend fun openSession(): Session? = withContext(Dispatchers.Blocking) {
        db.readableDatabase.rawQuery(
            "SELECT id FROM sessions WHERE ended_at IS NULL ORDER BY started_at DESC LIMIT 1",
            null,
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    }?.let { load(it) }

    suspend fun end(
        id: Long,
        rating: Int? = null,
        notes: String = "",
        now: Instant = Clock.System.now(),
    ): Unit = withContext(Dispatchers.Blocking) {
        val session = loadRow(id) ?: return@withContext
        // Ending while paused folds the pause in: the paused stretch was not
        // practice, and the session is over at the moment "End" is pressed.
        val paused = session.pausedSeconds + pauseSoFar(session, now)
        val values = SqlValues().apply {
            put("ended_at", now.toEpochMilliseconds())
            put("duration_sec", session.copy(endedAt = now, pausedSeconds = paused, pausedAt = null).durationSeconds())
            put("paused_seconds", paused)
            putNull("paused_at")
            if (rating != null) put("rating", rating) else putNull("rating")
            put("notes", notes)
            put("updated_at", now.toEpochMilliseconds())
        }
        db.writableDatabase.update("sessions", values, "id = ?", arrayOf(id.toString()))
    }

    /**
     * Pauses the clock. Stored as a timestamp, like the start, so a
     * pause survives the app being killed: a session recovered while paused
     * still knows how long it had been practiced.
     */
    suspend fun pause(id: Long, now: Instant = Clock.System.now()): Unit = withContext(Dispatchers.Blocking) {
        val values = SqlValues().apply { put("paused_at", now.toEpochMilliseconds()); put("updated_at", now.toEpochMilliseconds()) }
        db.writableDatabase.update("sessions", values, "id = ? AND paused_at IS NULL AND ended_at IS NULL", arrayOf(id.toString()))
    }

    suspend fun resume(id: Long, now: Instant = Clock.System.now()): Unit = withContext(Dispatchers.Blocking) {
        val session = loadRow(id) ?: return@withContext
        if (session.pausedAt == null) return@withContext
        val values = SqlValues().apply {
            put("paused_seconds", session.pausedSeconds + pauseSoFar(session, now))
            putNull("paused_at")
            put("updated_at", now.toEpochMilliseconds())
        }
        db.writableDatabase.update("sessions", values, "id = ?", arrayOf(id.toString()))
    }

    private fun pauseSoFar(session: Session, now: Instant): Long =
        session.pausedAt?.let { ((now.toEpochMilliseconds() - it.toEpochMilliseconds()) / 1000).coerceAtLeast(0) } ?: 0

    /**
     * Corrects a finished session.
     * The length is what the player says it was, so the pause is cleared: the end
     * time is derived from start + length, and stats read from those.
     */
    suspend fun update(
        id: Long,
        startedAt: Instant,
        durationSeconds: Long,
        rating: Int?,
        notes: String,
    ): Unit = withContext(Dispatchers.Blocking) {
        val values = SqlValues().apply {
            put("started_at", startedAt.toEpochMilliseconds())
            put("ended_at", (startedAt + (durationSeconds).seconds).toEpochMilliseconds())
            put("duration_sec", durationSeconds)
            put("paused_seconds", 0)
            putNull("paused_at")
            if (rating != null) put("rating", rating) else putNull("rating")
            put("notes", notes)
            put("updated_at", Clock.System.now().toEpochMilliseconds())
        }
        db.writableDatabase.update("sessions", values, "id = ? AND ended_at IS NOT NULL", arrayOf(id.toString()))
    }

    /** A session practiced away from the app, logged after the fact. */
    suspend fun addManual(
        startedAt: Instant,
        durationSeconds: Long,
        rating: Int?,
        notes: String,
    ): Long = withContext(Dispatchers.Blocking) {
        val values = SqlValues().apply {
            put("started_at", startedAt.toEpochMilliseconds())
            put("ended_at", (startedAt + (durationSeconds).seconds).toEpochMilliseconds())
            put("mode", SessionMode.FREE.name.lowercase())
            put("duration_sec", durationSeconds)
            if (rating != null) put("rating", rating) else putNull("rating")
            put("notes", notes)
            put("updated_at", Clock.System.now().toEpochMilliseconds())
        }
        db.writableDatabase.insert("sessions", null, values)
    }

    /** Throws away a session and its items — for the "that was too short" case. */
    suspend fun discard(id: Long): Unit = withContext(Dispatchers.Blocking) {
        db.writableDatabase.delete("sessions", "id = ?", arrayOf(id.toString()))
    }

    suspend fun addItem(item: SessionItem): Long = withContext(Dispatchers.Blocking) {
        val values = SqlValues().apply {
            put("session_id", item.sessionId)
            if (item.songId != null) put("song_id", item.songId) else putNull("song_id")
            put("exercise_name", item.exerciseName)
            put("seconds", item.seconds)
            if (item.bpmStart != null) put("bpm_start", item.bpmStart) else putNull("bpm_start")
            if (item.bpmEnd != null) put("bpm_end", item.bpmEnd) else putNull("bpm_end")
            put("notes", item.notes)
            if (item.partId != null) put("part_id", item.partId) else putNull("part_id")
        }
        db.writableDatabase.insert("session_items", null, values).also { touch(db.writableDatabase, item.sessionId) }
    }

    /**
     * Marks a session changed. Its items have no time of their own, so a
     * merge on another device carries them with the session, and only when
     * the session says it is newer.
     */
    private fun touch(db: SqlDatabase, sessionId: Long) {
        db.update("sessions", SqlValues().apply { put("updated_at", Clock.System.now().toEpochMilliseconds()) },
            "id = ?", arrayOf(sessionId.toString()))
    }

    /**
     * Replaces a session's tags and nothing else: items from the
     * speed trainer, a routine or the Practice button are left alone, because
     * only tag items carry [Tagging.MARK].
     */
    suspend fun replaceTags(sessionId: Long, tags: List<com.riffbuddy.domain.SessionTag>): Unit = withContext(Dispatchers.Blocking) {
        val db = db.writableDatabase
        db.beginTransaction()
        try {
            db.delete("session_items", "session_id = ? AND notes = ?", arrayOf(sessionId.toString(), com.riffbuddy.domain.Tagging.MARK))
            tags.forEach { t ->
                db.insert("session_items", null, SqlValues().apply {
                    put("session_id", sessionId)
                    put("song_id", t.songId)
                    if (t.partId != null) put("part_id", t.partId) else putNull("part_id")
                    put("seconds", t.seconds)
                    put("notes", com.riffbuddy.domain.Tagging.MARK)
                })
            }
            touch(db, sessionId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    suspend fun load(id: Long): Session? = withContext(Dispatchers.Blocking) {
        loadRow(id)?.let { it.copy(items = itemsFor(id)) }
    }

    private fun loadRow(id: Long): Session? =
        db.readableDatabase.rawQuery(
            "SELECT $SESSION_COLUMNS FROM sessions WHERE id = ?",
            arrayOf(id.toString()),
        ).use { c -> if (c.moveToFirst()) c.toSession() else null }

    /** Every session with its items, for the stats screen. */
    suspend fun all(): List<Session> = withContext(Dispatchers.Blocking) {
        val sessions = db.readableDatabase.rawQuery(
            "SELECT $SESSION_COLUMNS FROM sessions ORDER BY started_at DESC",
            null,
        ).use { c -> buildList { while (c.moveToNext()) add(c.toSession()) } }
        // One query for all the items rather than one per session: a year of
        // daily practice is a few hundred sessions, and that would be a few
        // hundred round trips every time the Log screen opened.
        val itemsBySession = allItems()
        sessions.map { it.copy(items = itemsBySession[it.id].orEmpty()) }
    }

    private fun itemsFor(sessionId: Long): List<SessionItem> =
        db.readableDatabase.rawQuery(
            "SELECT $ITEM_COLUMNS FROM session_items WHERE session_id = ?",
            arrayOf(sessionId.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(c.toItem()) } }

    private fun allItems(): Map<Long, List<SessionItem>> =
        db.readableDatabase.rawQuery("SELECT $ITEM_COLUMNS FROM session_items", null)
            .use { c ->
                buildList { while (c.moveToNext()) add(c.toItem()) }
            }.groupBy { it.sessionId }

    private fun SqlCursor.toSession() = Session(
        id = getLong(0),
        startedAt = Instant.fromEpochMilliseconds(getLong(1)),
        endedAt = if (isNull(2)) null else Instant.fromEpochMilliseconds(getLong(2)),
        mode = runCatching { SessionMode.valueOf(requireString(3).uppercase()) }
            .getOrDefault(SessionMode.FREE),
        rating = if (isNull(4)) null else getInt(4),
        notes = getString(5) ?: "",
        pausedSeconds = getLong(6),
        pausedAt = if (isNull(7)) null else Instant.fromEpochMilliseconds(getLong(7)),
    )

    private fun SqlCursor.toItem() = SessionItem(
        id = getLong(0),
        sessionId = getLong(1),
        songId = if (isNull(2)) null else getLong(2),
        exerciseName = getString(3),
        seconds = getInt(4),
        bpmStart = if (isNull(5)) null else getInt(5),
        bpmEnd = if (isNull(6)) null else getInt(6),
        notes = getString(7) ?: "",
        partId = if (isNull(8)) null else getLong(8),
    )

    private companion object {
        const val SESSION_COLUMNS =
            "id, started_at, ended_at, mode, rating, notes, paused_seconds, paused_at"
        const val ITEM_COLUMNS =
            "id, session_id, song_id, exercise_name, seconds, bpm_start, bpm_end, notes, part_id"
    }
}
