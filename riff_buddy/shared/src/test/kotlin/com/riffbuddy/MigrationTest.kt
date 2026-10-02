package com.riffbuddy

import com.riffbuddy.data.RecordingRepo
import com.riffbuddy.data.Schema
import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A fixture of an older database, upgraded with no data loss.
 *
 * Runs the real SQL against a real SQLite (sqlite-jdbc, test-only), which is
 * what reading the statements could never prove. The JVM's SQLite is newer than
 * Android 9's 3.22, so this says nothing about dialect — SqliteDialectTest covers
 * that — but everything about whether the data survives.
 */
class MigrationTest {

    private val open = mutableListOf<Connection>()

    private fun database(): Connection =
        DriverManager.getConnection("jdbc:sqlite::memory:").also {
            open += it
            // As Db.onConfigure does. Without it every ON DELETE is inert and
            // the SET NULL test below would pass for the wrong reason.
            it.createStatement().use { s -> s.execute("PRAGMA foreign_keys = ON") }
        }

    @After
    fun close() = open.forEach(Connection::close)

    private fun Connection.run(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.count(table: String): Int =
        createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM $table").use { it.getInt(1) } }

    private fun Connection.single(sql: String): Any? =
        createStatement().use { s -> s.executeQuery(sql).use { if (it.next()) it.getObject(1) else null } }

    /** What Db.onUpgrade does, step for step. */
    private fun Connection.upgrade(from: Int, to: Int = Schema.VERSION) {
        var v = from
        while (v < to) {
            Schema.MIGRATIONS[v]?.forEach { run(it) }
            v++
        }
    }

    /** A v1 database with a little of everything a real one holds. */
    private fun v1WithData(): Connection = database().apply {
        Schema.V1.forEach { run(it) }
        run("INSERT INTO songs (id, title, artist, songsterr_url, created_at, updated_at) VALUES (1, 'Floods', 'Pantera', 'https://www.songsterr.com/a/wsa/pantera-floods-tab-s403', 1, 2)")
        run("INSERT INTO songs (id, title, artist, created_at, updated_at) VALUES (2, 'Little Wing', 'Jimi Hendrix', 3, 4)")
        run("INSERT INTO sessions (id, started_at, ended_at, duration_sec, rating) VALUES (1, 1000, 2800000, 2799, 4)")
        run("INSERT INTO session_items (session_id, song_id, seconds, bpm_end) VALUES (1, 1, 1500, 84)")
        run("INSERT INTO settings (key, value) VALUES ('ai_model_gemini', 'gemma-4-26b-a4b-it')")
    }

    @Test
    fun `upgrading v1 to the current version keeps every row`() {
        val db = v1WithData()
        val before = listOf("songs", "sessions", "session_items", "settings").associateWith { db.count(it) }

        db.upgrade(from = 1)

        before.forEach { (table, rows) -> assertEquals("$table lost rows", rows, db.count(table)) }
        // Not just the counts: a link that was once lost in an upgrade survives.
        assertEquals(
            "https://www.songsterr.com/a/wsa/pantera-floods-tab-s403",
            db.single("SELECT songsterr_url FROM songs WHERE id = 1"),
        )
        assertEquals("gemma-4-26b-a4b-it", db.single("SELECT value FROM settings WHERE key = 'ai_model_gemini'"))
        assertEquals(84, db.single("SELECT bpm_end FROM session_items WHERE song_id = 1"))
    }

    @Test
    fun `the upgraded database has the tab library and it works`() {
        val db = v1WithData()
        db.upgrade(from = 1)

        db.run(
            "INSERT INTO tab_files (song_id, name, original_name, stored_name, size_bytes, sha256, created_at, updated_at) " +
                "VALUES (1, 'Floods', 'floods.gp5', 'a1b2.gp5', 81234, 'abc', 5, 5)"
        )
        assertEquals(100, db.single("SELECT speed_percent FROM tab_files"))
        assertEquals(null, db.single("SELECT loop_start_bar FROM tab_files"))
    }

    /** v9: a tab file from before remembers nothing extra, and keeps everything it had. */
    @Test
    fun `v9 adds tab player settings without touching a tab file`() {
        val db = database()
        Schema.V1.forEach { db.run(it) }
        db.upgrade(from = 1, to = 8)
        db.run(
            "INSERT INTO tab_files (name, original_name, stored_name, size_bytes, sha256, speed_percent, loop_start_bar, loop_end_bar, created_at, updated_at) " +
                "VALUES ('Floods', 'floods.gp5', 'a1b2.gp5', 1, 'abc', 70, 5, 8, 5, 5)"
        )
        db.upgrade(from = 8)
        assertEquals(null, db.single("SELECT player_prefs FROM tab_files"))
        assertEquals(70, db.single("SELECT speed_percent FROM tab_files"))
        db.run("UPDATE tab_files SET player_prefs = 't2=m,-,1.0'")
        assertEquals("t2=m,-,1.0", db.single("SELECT player_prefs FROM tab_files"))
    }

    /** v10: an amp from before has no details, and keeps everything it had. */
    @Test
    fun `v10 adds amp details without touching an amp`() {
        val db = database()
        Schema.V1.forEach { db.run(it) }
        db.upgrade(from = 1, to = 9)
        db.run("INSERT INTO amps (name, make, model, type, controls_json, notes) VALUES ('LT25', 'Fender', 'Mustang LT25', 'modeling', '[]', '')")
        db.upgrade(from = 9)
        assertEquals(null, db.single("SELECT details_json FROM amps"))
        assertEquals("Mustang LT25", db.single("SELECT model FROM amps"))
    }

    /** Deleting a song keeps its tab file, unlinked, rather than losing an import. */
    @Test
    fun `deleting a song unlinks its tab file instead of deleting it`() {
        val db = v1WithData()
        db.upgrade(from = 1)
        db.run(
            "INSERT INTO tab_files (song_id, name, original_name, stored_name, size_bytes, sha256, created_at, updated_at) " +
                "VALUES (2, 'Little Wing', 'lw.gp', 'c3d4.gp', 1, 'def', 5, 5)"
        )

        db.run("DELETE FROM songs WHERE id = 2")

        assertEquals(1, db.count("tab_files"))
        assertEquals(null, db.single("SELECT song_id FROM tab_files"))
    }

    /**
     * A fresh install and an upgraded one must be the same database. They are
     * built from the same statements (Schema.fresh), but this is the check that
     * stays true if someone later "simplifies" that.
     */
    @Test
    fun `a fresh install and an upgraded install have identical schemas`() {
        val fresh = database().apply { Schema.fresh().forEach { run(it) } }
        val upgraded = database().apply { Schema.V1.forEach { run(it) }; upgrade(from = 1) }

        fun Connection.shape(): List<String> = createStatement().use { s ->
            s.executeQuery(
                "SELECT type || ' ' || name || ' ' || IFNULL(sql, '') FROM sqlite_master " +
                    "WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name"
            ).use { r -> buildList { while (r.next()) add(r.getString(1)) } }
        }

        assertEquals(upgraded.shape(), fresh.shape())
        assertTrue(fresh.shape().any { it.startsWith("table tab_files ") })
    }

    @Test
    fun `every version below the current one has a migration`() {
        (1 until Schema.VERSION).forEach { v ->
            assertTrue("no migration from v$v", Schema.MIGRATIONS[v]?.isNotEmpty() == true)
        }
    }

    /** A saved loop belongs to one file, and goes with it. */
    @Test
    fun `a saved loop names exactly one file and is deleted with it`() {
        val db = v1WithData()
        db.upgrade(from = 1)
        db.run(
            "INSERT INTO audio_files (id, name, original_name, uri, created_at, updated_at) " +
                "VALUES (1, 'Floods', 'floods.mp3', 'content://x/1', 1, 1)"
        )
        db.run(
            "INSERT INTO saved_loops (audio_id, name, start_value, end_value, created_at, updated_at) " +
                "VALUES (1, 'Solo run', 81000, 86500, 1, 1)"
        )
        val neither = runCatching {
            db.run("INSERT INTO saved_loops (name, start_value, end_value, created_at, updated_at) VALUES ('x', 1, 2, 1, 1)")
        }
        assertTrue("a loop with no file was accepted", neither.isFailure)

        db.run("DELETE FROM audio_files WHERE id = 1")
        assertEquals(0, db.count("saved_loops"))
    }

    /**
     * Deleting a take takes its "Loop it" copy with it, and nothing else.
     * Without this, the looper would still list a deleted take.
     */
    @Test
    fun `deleting a take removes the looper entry made from it`() {
        val db = v1WithData()
        db.upgrade(from = 1)
        val stored = "012a46ff-ca06-43c2-8682-25ac116c3439.wav"
        db.run(
            "INSERT INTO audio_files (id, name, original_name, uri, created_at, updated_at) VALUES " +
                "(1, 'Take 1', 'Take 1.wav', 'file:///data/user/0/x/files/recordings/$stored', 1, 1), " +
                "(2, 'Floods', 'floods.mp3', 'content://x/1', 1, 1), " +
                "(3, 'Other take', 'o.wav', 'file:///data/user/0/x/files/recordings/other-$stored', 1, 1)"
        )
        db.run(
            "INSERT INTO saved_loops (audio_id, name, start_value, end_value, created_at, updated_at) " +
                "VALUES (1, 'Bend', 3686, 12582, 1, 1)"
        )
        db.run(
            "DELETE FROM audio_files WHERE " +
                RecordingRepo.LOOPER_COPY_WHERE.replace("?", "'${RecordingRepo.looperCopyPattern(stored)}'")
        )
        assertEquals(2, db.count("audio_files"))
        assertEquals(0, db.count("saved_loops"))
    }

    /**
     * "Retired" became "Mastered", in the data too; parts go with
     * their song; a deleted part leaves its minutes on the song.
     */
    @Test
    fun `v8 masters retired songs and links parts to minutes`() {
        val db = database().apply {
            Schema.V1.forEach { run(it) }
            (1 until 7).forEach { v -> Schema.MIGRATIONS.getValue(v).forEach { run(it) } }
            run("INSERT INTO songs (id, title, status, created_at, updated_at) VALUES (1, 'Floods', 'RETIRED', 1, 2)")
            run("INSERT INTO songs (id, title, status, created_at, updated_at) VALUES (2, 'Little Wing', 'LEARNING', 3, 4)")
            run("INSERT INTO sessions (id, started_at, ended_at, duration_sec) VALUES (1, 1000, 2800000, 2799)")
        }
        Schema.MIGRATIONS.getValue(7).forEach { db.run(it) }

        // The row keeps its old word; the app reads it as Mastered (MasteryTest).
        assertEquals("RETIRED", db.single("SELECT status FROM songs WHERE id = 1"))
        assertEquals(com.riffbuddy.domain.SongStatus.MASTERED,
            com.riffbuddy.domain.SongStatus.fromName(db.single("SELECT status FROM songs WHERE id = 1") as String))

        db.run("INSERT INTO song_parts (id, song_id, name, created_at, updated_at) VALUES (10, 2, 'Solo', 1, 1)")
        assertEquals("NOT_STARTED", db.single("SELECT stage FROM song_parts WHERE id = 10"))
        db.run("INSERT INTO session_items (session_id, song_id, part_id, seconds) VALUES (1, 2, 10, 600)")

        db.run("DELETE FROM song_parts WHERE id = 10")
        assertEquals(1, db.count("session_items"))
        assertEquals(null, db.single("SELECT part_id FROM session_items"))
        assertEquals(600, db.single("SELECT seconds FROM session_items"))

        db.run("INSERT INTO song_parts (song_id, name, created_at, updated_at) VALUES (2, 'Intro', 1, 1)")
        db.run("DELETE FROM songs WHERE id = 2")
        assertEquals(0, db.count("song_parts"))
    }

    /** A v2 install — the tab library, with files in it — upgrades too. */
    @Test
    fun `upgrading from v2 keeps the tab library`() {
        // A genuine v2: v1, then only the v1 -> v2 migration.
        val v2 = database().apply {
            Schema.V1.forEach { run(it) }
            Schema.MIGRATIONS.getValue(1).forEach { run(it) }
            run(
                "INSERT INTO tab_files (name, original_name, stored_name, size_bytes, sha256, loop_start_bar, loop_end_bar, created_at, updated_at) " +
                    "VALUES ('Study', 's.gp', 'a.gp', 1, 'h', 5, 8, 1, 1)"
            )
        }
        v2.upgrade(from = 2)
        assertEquals(1, v2.count("tab_files"))
        assertEquals(8, v2.single("SELECT loop_end_bar FROM tab_files"))
        assertEquals(0, v2.count("audio_files"))
    }

    /** Deleting the song or the session keeps the take. */
    @Test
    fun `a take outlives its song and its session`() {
        val db = v1WithData()
        db.upgrade(from = 1)
        db.run(
            "INSERT INTO recordings (song_id, session_id, name, stored_name, sample_rate, created_at, updated_at) " +
                "VALUES (1, 1, 'Take 1', 'a.wav', 48000, 1, 1)"
        )
        db.run("DELETE FROM songs WHERE id = 1")
        db.run("DELETE FROM sessions WHERE id = 1")
        assertEquals(1, db.count("recordings"))
        assertEquals(null, db.single("SELECT song_id FROM recordings"))
        assertEquals(null, db.single("SELECT session_id FROM recordings"))
        assertEquals(0, db.single("SELECT latency_offset_ms FROM recordings"))
    }

    @Test
    fun `a backing track keeps its recipe and outlives its song`() {
        val db = v1WithData()
        db.upgrade(from = 1)
        db.run(
            "INSERT INTO backing_tracks (song_id, name, spec_json, created_at, updated_at) " +
                "VALUES (1, 'Floods jam', '{}', 1, 1)"
        )
        db.run("DELETE FROM songs WHERE id = 1")
        assertEquals(1, db.count("backing_tracks"))
        assertEquals(null, db.single("SELECT song_id FROM backing_tracks"))
    }

    /** Deleting a linked song keeps the homework text. */
    @Test
    fun `homework keeps its text when its song and its lesson are deleted`() {
        val db = v1WithData()
        db.upgrade(from = 1)
        db.run("INSERT INTO lessons (id, lesson_date, teacher, created_at, updated_at) VALUES (1, 1, 'Sam', 1, 1)")
        db.run(
            "INSERT INTO homework (lesson_id, song_id, text, created_at, updated_at) " +
                "VALUES (1, 1, 'Clean up the outro run at 70 BPM', 1, 1)"
        )
        db.run("DELETE FROM songs WHERE id = 1")
        db.run("DELETE FROM lessons WHERE id = 1")
        assertEquals("Clean up the outro run at 70 BPM", db.single("SELECT text FROM homework"))
        assertEquals(null, db.single("SELECT song_id FROM homework"))
        assertEquals(null, db.single("SELECT lesson_id FROM homework"))
    }

    @Test
    fun `a routine's steps go with it`() {
        val db = v1WithData()
        db.upgrade(from = 1)
        db.run("INSERT INTO routines (id, name, created_at, updated_at) VALUES (1, 'Daily', 1, 1)")
        db.run("INSERT INTO routine_steps (routine_id, position, kind, title, minutes) VALUES (1, 0, 'WARM_UP', 'Chromatic', 3)")
        db.run("DELETE FROM routines WHERE id = 1")
        assertEquals(0, db.count("routine_steps"))
    }

    @Test
    fun `v7 gives old sessions no pause and keeps their length`() {
        val db = v1WithData()
        db.upgrade(from = 1)
        assertEquals(0, db.single("SELECT paused_seconds FROM sessions WHERE id = 1"))
        assertEquals(null, db.single("SELECT paused_at FROM sessions WHERE id = 1"))
        assertEquals(2799, db.single("SELECT duration_sec FROM sessions WHERE id = 1"))
    }
}
