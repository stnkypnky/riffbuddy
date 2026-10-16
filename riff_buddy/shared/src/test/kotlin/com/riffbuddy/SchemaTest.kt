package com.riffbuddy

import com.riffbuddy.data.Schema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards on the schema that are worth having before there is anything to guard.
 *
 * These run on the JVM with `./gradlew :apps:riff_buddy:test` — no device, no
 * emulator — because [Schema] deliberately has no Android imports.
 */
class SchemaTest {

    /** Everything a fresh install runs, so new tables are held to the same rules. */
    private val sql = Schema.fresh().joinToString("\n")

    private val tables: List<String> = Regex("CREATE TABLE (\\w+)")
        .findAll(sql).map { it.groupValues[1] }.toList()

    /** Every core entity has somewhere to live. */
    @Test
    fun `mvp entities all have tables`() {
        val expected = listOf(
            "songs", "sessions", "session_items", "tunings",
            "guitars", "amps", "pedals", "rigs",
            "tone_cards", "ai_messages", "metronome_presets", "settings",
        )
        val missing = expected - tables.toSet()
        assertTrue("no table for: $missing", missing.isEmpty())
    }

    @Test
    fun `no table is declared twice`() {
        assertEquals(tables.distinct(), tables)
    }

    /**
     * API keys live in secure storage, never in the database — because the
     * backup export is built *from* the database, so a key stored here would end
     * up in a backup file no matter how carefully the exporter is written.
     *
     * This is the cheapest possible enforcement of that, and it will fail the
     * moment someone adds the obvious convenient column.
     */
    @Test
    fun `no column looks like a secret`() {
        val forbidden = listOf("api_key", "apikey", "secret", "token", "password")
        val columns = sql.lowercase()
        forbidden.forEach { word ->
            assertTrue("schema contains a '$word' column", !columns.contains(word))
        }
    }

    /**
     * Timestamps are epoch millis in INTEGER columns, never TEXT dates — the
     * session timer and the stats both do arithmetic on them.
     */
    @Test
    fun `timestamp columns are integers`() {
        // Declarations only: a trigger's body names columns too ("CAST(x_at AS TEXT)").
        val declarations = Schema.fresh().filterNot { it.trim().startsWith("CREATE TRIGGER") }.joinToString("\n")
        Regex("(\\w*_at)\\s+(\\w+)").findAll(declarations).forEach { m ->
            assertEquals(
                "${m.groupValues[1]} should be INTEGER epoch millis",
                "INTEGER",
                m.groupValues[2],
            )
        }
    }

    /**
     * Only DDL here — seeding reference data is the app's job, not the
     * migration's — and only DDL that adds. CREATE, or ALTER TABLE … ADD
     * COLUMN; never a DROP or a RENAME, which Android 9's SQLite 3.22 may not
     * have and which lose data on the way. Migrations only add.
     */
    @Test
    fun `every statement is additive ddl`() {
        Schema.fresh().forEach {
            val sql = it.trim().replace(Regex("\\s+"), " ").uppercase()
            val ok = sql.startsWith("CREATE ") ||
                (sql.startsWith("ALTER TABLE ") && sql.contains(" ADD COLUMN "))
            assertTrue("not additive DDL: ${it.trim().take(60)}", ok)
        }
    }

    /**
     * Every table is either backed up or deliberately left out, and says
     * which. The failure this stops is the quiet one — a new table missing from
     * backups by default, discovered on the day a restore comes back without it.
     */
    @Test
    fun `every table is backed up or deliberately excluded`() {
        val backedUp = com.riffbuddy.data.BackupFormat.TABLES.toSet()
        val excluded = com.riffbuddy.data.BackupFormat.EXCLUDED
        assertEquals("a table is both backed up and excluded", emptySet<String>(), backedUp intersect excluded)
        assertEquals(
            "schema tables and backup lists disagree",
            tables.toSet(),
            backedUp + excluded,
        )
    }

    /**
     * A deletion reaches the other devices only if its table notes it. Every
     * backed-up table whose items a merge can recognize (by a creation time,
     * or its own override) needs a trigger; a new one added without it would
     * have its deletions undone by the next sync.
     */
    @Test
    fun `every recognizable table notes its deletions`() {
        val triggers = Regex("CREATE TRIGGER (\\w+)_deleted AFTER DELETE ON (\\w+)").findAll(sql)
            .map { it.groupValues[2] }.toSet()
        val recognizable = Regex("CREATE TABLE (\\w+) \\(([^;]*?)\\n\\s*\\)", RegexOption.DOT_MATCHES_ALL).findAll(sql)
            .filter { m ->
                val table = m.groupValues[1]
                table in com.riffbuddy.data.BackupFormat.TABLES &&
                    (table in com.riffbuddy.data.BackupMerge.IDENTITY_OVERRIDES ||
                        com.riffbuddy.data.BackupMerge.IDENTITY_COLUMNS.any { Regex("\\b$it\\b").containsMatchIn(m.groupValues[2]) })
            }.map { it.groupValues[1] }.toSet()
        assertTrue("found no tables to check", recognizable.size > 10)
        assertEquals("tables whose deletions would not travel", emptySet<String>(), recognizable - triggers)
    }
}
