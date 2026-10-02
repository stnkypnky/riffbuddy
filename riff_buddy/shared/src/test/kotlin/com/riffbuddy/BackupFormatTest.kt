package com.riffbuddy

import com.riffbuddy.data.BackupFormat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class BackupFormatTest {

    private fun backup(
        version: Int = BackupFormat.VERSION,
        format: String = BackupFormat.MAGIC,
        withTables: Boolean = true,
    ) = JSONObject()
        .put("format", format)
        .put("schema_version", version)
        .put("app_version", "0.1.0")
        .put("created_at", Instant.now().toEpochMilli())
        .apply {
            if (withTables) put(
                "tables",
                JSONObject().put("songs", JSONArray().put(JSONObject().put("title", "x"))),
            )
        }

    @Test
    fun `a good backup validates`() {
        assertNull(BackupFormat.validate(backup()))
    }

    @Test
    fun `some other json file is refused`() {
        val problem = BackupFormat.validate(JSONObject().put("hello", "world"))
        assertEquals(BackupFormat.Problem.NotABackup, problem)
        assertTrue(problem!!.message.contains("not a Riff Buddy backup"))
    }

    /**
     * The case that matters most. A newer format may carry fields this version
     * would silently drop, and a restore that quietly loses data is the worst
     * possible outcome for a feature whose only job is not losing data.
     */
    @Test
    fun `a backup from a newer version is refused, not partially read`() {
        val problem = BackupFormat.validate(backup(version = BackupFormat.VERSION + 1))
        assertTrue(problem is BackupFormat.Problem.TooNew)
        assertTrue(problem!!.message.contains("newer version"))
        assertTrue("the message should say what to do", problem.message.contains("Update the app"))
    }

    @Test
    fun `a damaged or empty backup is refused with a reason`() {
        assertTrue(BackupFormat.validate(backup(version = 0)) is BackupFormat.Problem.Damaged)
        assertTrue(BackupFormat.validate(backup(withTables = false)) is BackupFormat.Problem.Damaged)
    }

    @Test
    fun `the summary counts only tables that have rows`() {
        val root = backup().put(
            "tables",
            JSONObject()
                .put("songs", JSONArray().put(JSONObject()).put(JSONObject()))
                .put("sessions", JSONArray()),
        )
        val summary = BackupFormat.summarize(root)
        assertEquals(2, summary["songs"])
        assertFalse("an empty table is noise in a summary", summary.containsKey("sessions"))
    }

    /**
     * Keys live in secure storage and never enter
     * the database, so there is nothing to strip. This test exists so that a
     * future change putting one in a table fails here rather than shipping a
     * backup with a secret in it.
     */
    @Test
    fun `a backup with a key-shaped field is detected`() {
        assertFalse(BackupFormat.containsSecrets(backup()))
        val leaky = backup().put(
            "tables",
            JSONObject().put("settings", JSONArray().put(JSONObject().put("api_key", "sk-123"))),
        )
        assertTrue(BackupFormat.containsSecrets(leaky))
    }

    /** The cache is deliberately not backed up: stale answers and a big file. */
    @Test
    fun `the ai cache is not in the backup`() {
        assertFalse(BackupFormat.TABLES.contains("ai_cache"))
        assertTrue(BackupFormat.TABLES.contains("tone_cards"))
    }

    /** Parents before children, so a restore does not break foreign keys. */
    @Test
    fun `tables are ordered so references resolve`() {
        val order = BackupFormat.TABLES
        assertTrue(order.indexOf("songs") < order.indexOf("session_items"))
        assertTrue(order.indexOf("sessions") < order.indexOf("session_items"))
        assertTrue(order.indexOf("songs") < order.indexOf("tone_cards"))
        assertTrue(order.indexOf("rigs") < order.indexOf("tone_cards"))
        assertTrue(order.indexOf("guitars") < order.indexOf("rigs"))
        assertTrue(order.indexOf("tunings") < order.indexOf("songs"))
        assertTrue(order.indexOf("songs") < order.indexOf("tab_files"))
    }

    /** A gentle nudge after thirty days, and never before. */
    @Test
    fun `the reminder waits thirty days`() {
        val now = Instant.now()
        assertFalse(BackupFormat.needsReminder(now.minus(29, ChronoUnit.DAYS), now))
        assertTrue(BackupFormat.needsReminder(now.minus(30, ChronoUnit.DAYS), now))
        // Never backed up at all is not a nag — there may be nothing worth saving.
        assertFalse(BackupFormat.needsReminder(null, now))
    }

    @Test
    fun `the filename is recognizable among a hundred downloads`() {
        val name = BackupFormat.suggestedFileName(Instant.parse("2026-09-21T12:00:00Z"))
        assertTrue(name.startsWith("riff-buddy-"))
        assertTrue(name.endsWith(".riffbuddy"))
        assertTrue(name.contains("2026"))
    }

    @Test
    fun `both restore modes explain themselves`() {
        BackupFormat.Mode.entries.forEach {
            assertTrue(it.label.isNotBlank())
            assertTrue("a destructive choice must say so", it.explanation.length > 20)
        }
        assertTrue(BackupFormat.Mode.REPLACE.explanation.contains("Wipes"))
    }

    // ---- tab files in backups -----------------------------------------------

    @Test
    fun `stored names the app creates are safe to restore`() {
        assertTrue(BackupFormat.isSafeStoredName("3f2b8c1e-9a4d-4e1f-8b2a-6c5d4e3f2a1b.gp5"))
        assertTrue(BackupFormat.isSafeStoredName("3f2b8c1e-9a4d-4e1f-8b2a-6c5d4e3f2a1b.musicxml"))
    }

    /** Zip slip: a backup must not be able to write outside the tab folder. */
    @Test
    fun `names that could escape the tab folder are refused`() {
        listOf(
            "../databases/riffbuddy.db",
            "../../shared_prefs/keys.xml",
            "3f2b8c1e-9a4d-4e1f-8b2a-6c5d4e3f2a1b.gp5/../../x",
            "/data/data/com.riffbuddy/databases/riffbuddy.db",
            "..",
            "",
            "3f2b8c1e-9a4d-4e1f-8b2a-6c5d4e3f2a1b.gp5\u0000.txt",
            "My Song.gp5",
        ).forEach { assertFalse("accepted: $it", BackupFormat.isSafeStoredName(it)) }
    }

    @Test
    fun `a version 1 backup still restores after the format moved to 2`() {
        val old = BackupFormat.manifest("0.1.0").put("schema_version", 1).put("tables", org.json.JSONObject())
        assertEquals(null, BackupFormat.validate(old))
    }
}
