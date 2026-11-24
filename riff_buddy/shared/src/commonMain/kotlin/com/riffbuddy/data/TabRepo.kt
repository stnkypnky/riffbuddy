package com.riffbuddy.data

import com.riffbuddy.domain.TabFile
import com.riffbuddy.domain.TabPrefs
import com.riffbuddy.platform.Blocking
import com.riffbuddy.tabs.TabLibrary
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** How an import ended, when it did not fail. */
sealed interface Imported {
    val file: TabFile

    data class Added(override val file: TabFile) : Imported

    /**
     * The same bytes were already in the library. Not an error and not a second
     * copy: re-downloading a file already in the library should land on the
     * one that is there.
     */
    data class AlreadyHad(override val file: TabFile) : Imported
}

/**
 * The tab library: rows here, bytes in [library].
 *
 * The order inside [import] is the guarantee. Judge the file (nothing written),
 * then write the bytes, then the row — and if the row fails, take the bytes back
 * out. A library row therefore always has a file behind it, and a failed import
 * leaves the disk and the database as it found them.
 */
class TabRepo(private val db: Db, private val library: TabLibrary) {

    suspend fun all(): List<TabFile> = withContext(Dispatchers.Blocking) {
        db.readableDatabase.rawQuery("SELECT $COLUMNS FROM tab_files ORDER BY updated_at DESC", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.toTabFile()) } }
    }

    suspend fun byId(id: Long): TabFile? = withContext(Dispatchers.Blocking) {
        db.readableDatabase.rawQuery("SELECT $COLUMNS FROM tab_files WHERE id = ?", arrayOf(id.toString()))
            .use { c -> if (c.moveToFirst()) c.toTabFile() else null }
    }

    suspend fun import(bytes: ByteArray, originalName: String, songId: Long? = null): Result<Imported> {
        // Parsing is CPU work — a big file takes a noticeable moment on a slow tablet.
        val prepared = withContext(Dispatchers.Default) { library.prepare(bytes, originalName) }
            .getOrElse { return Result.failure(it) }

        return withContext(Dispatchers.Blocking) {
            findBySha(prepared.sha256)?.let { existing ->
                if (songId != null && existing.songId == null) link(existing.id, songId)
                return@withContext Result.success(Imported.AlreadyHad(byId(existing.id) ?: existing))
            }

            runCatching {
                library.write(prepared)
                val now = Clock.System.now().toEpochMilliseconds()
                val id = try {
                    db.writableDatabase.insertOrThrow(
                        "tab_files", null,
                        SqlValues().apply {
                            if (songId != null) put("song_id", songId) else putNull("song_id")
                            put("name", prepared.displayName)
                            put("original_name", prepared.originalName)
                            put("stored_name", prepared.storedName)
                            put("size_bytes", prepared.bytes.size.toLong())
                            put("sha256", prepared.sha256)
                            put("title", prepared.summary.title)
                            put("artist", prepared.summary.artist)
                            put("track_count", prepared.summary.tracks.size)
                            put("bar_count", prepared.summary.bars)
                            put("created_at", now)
                            put("updated_at", now)
                        },
                    )
                } catch (e: Exception) {
                    library.delete(prepared.storedName)
                    throw e
                }
                Imported.Added(byId(id) ?: error("imported row $id vanished"))
            }
        }
    }

    suspend fun rename(id: Long, name: String): Unit = withContext(Dispatchers.Blocking) {
        val clean = name.trim().take(120)
        if (clean.isEmpty()) return@withContext
        update(id, SqlValues().apply { put("name", clean) })
    }

    suspend fun link(id: Long, songId: Long?): Unit = withContext(Dispatchers.Blocking) {
        update(id, SqlValues().apply { if (songId != null) put("song_id", songId) else putNull("song_id") })
    }

    /** Row first, then bytes: a crash in between leaves an orphan file, never a row with nothing behind it. */
    suspend fun delete(file: TabFile): Unit = withContext(Dispatchers.Blocking) {
        db.writableDatabase.delete("tab_files", "id = ?", arrayOf(file.id.toString()))
        library.delete(file.storedName)
    }

    /** Null when the bytes are gone — say, a restore that brought the row but not the file. */
    suspend fun bytes(file: TabFile): ByteArray? = withContext(Dispatchers.Blocking) {
        if (library.exists(file.storedName)) library.read(file.storedName) else null
    }

    /**
     * Position, speed, loop, and the tracks and view settings
     * ([TabPrefs]), remembered per file. Written without
     * touching updated_at, so playing a file does not reorder the library under
     * the player's thumb.
     */
    suspend fun savePlayback(
        id: Long,
        positionMs: Long,
        speedPercent: Int,
        loopStartBar: Int?,
        loopEndBar: Int?,
        prefs: TabPrefs = TabPrefs(),
    ): Unit = withContext(Dispatchers.Blocking) {
        db.writableDatabase.update(
            "tab_files",
            SqlValues().apply {
                put("position_ms", positionMs.coerceAtLeast(0))
                put("speed_percent", speedPercent.coerceIn(TabFile.SPEED_RANGE))
                if (loopStartBar != null && loopEndBar != null) {
                    put("loop_start_bar", loopStartBar)
                    put("loop_end_bar", loopEndBar)
                } else {
                    putNull("loop_start_bar")
                    putNull("loop_end_bar")
                }
                prefs.encode()?.let { put("player_prefs", it) } ?: putNull("player_prefs")
            },
            "id = ?", arrayOf(id.toString()),
        )
    }

    private fun update(id: Long, values: SqlValues) {
        values.put("updated_at", Clock.System.now().toEpochMilliseconds())
        db.writableDatabase.update("tab_files", values, "id = ?", arrayOf(id.toString()))
    }

    private fun findBySha(sha: String): TabFile? =
        db.readableDatabase.rawQuery("SELECT $COLUMNS FROM tab_files WHERE sha256 = ? LIMIT 1", arrayOf(sha))
            .use { c -> if (c.moveToFirst()) c.toTabFile() else null }

    private fun SqlCursor.toTabFile() = TabFile(
        id = getLong(0),
        songId = if (isNull(1)) null else getLong(1),
        name = requireString(2),
        originalName = requireString(3),
        storedName = requireString(4),
        sizeBytes = getLong(5),
        sha256 = requireString(6),
        title = getString(7) ?: "",
        artist = getString(8) ?: "",
        trackCount = getInt(9),
        barCount = getInt(10),
        positionMs = getLong(11),
        speedPercent = getInt(12),
        loopStartBar = if (isNull(13)) null else getInt(13),
        loopEndBar = if (isNull(14)) null else getInt(14),
        createdAt = Instant.fromEpochMilliseconds(getLong(15)),
        updatedAt = Instant.fromEpochMilliseconds(getLong(16)),
        prefs = TabPrefs.decode(getString(17)),
    )

    private companion object {
        const val COLUMNS =
            "id, song_id, name, original_name, stored_name, size_bytes, sha256, title, artist, " +
                "track_count, bar_count, position_ms, speed_percent, loop_start_bar, loop_end_bar, " +
                "created_at, updated_at, player_prefs"
    }
}
