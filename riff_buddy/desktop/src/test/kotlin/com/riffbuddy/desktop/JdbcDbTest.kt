package com.riffbuddy.desktop

import com.riffbuddy.data.Conflict
import com.riffbuddy.data.Schema
import com.riffbuddy.data.SqlValues
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The desktop database has to answer every call the way Android's SQLite
 * does, because the repositories were written against Android's answers.
 * Each test here is one of those answers.
 */
class JdbcDbTest {

    private val dir = Files.createTempDirectory("riffbuddy-db").toFile()
    private val db = JdbcDb(File(dir, "test.db"))
    private val sql = db.writableDatabase

    @After
    fun tearDown() {
        db.close()
        dir.deleteRecursively()
    }

    private fun setting(key: String, value: String?) = SqlValues().apply { put("key", key); put("value", value) }

    @Test
    fun `a new file is created at the current schema version`() {
        assertEquals(Schema.VERSION, sql.version)
        val tables = sql.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.requireString(0)) } }
        assertTrue("settings" in tables && "songs" in tables && "recordings" in tables)
    }

    @Test
    fun `reopening a current file changes nothing`() {
        sql.insertOrThrow("settings", null, setting("a4_hz", "442"))
        db.close()
        val again = JdbcDb(File(dir, "test.db"))
        val v = again.readableDatabase.rawQuery("SELECT value FROM settings WHERE key = ?", arrayOf("a4_hz"))
            .use { if (it.moveToFirst()) it.getString(0) else null }
        assertEquals("442", v)
        again.close()
    }

    @Test
    fun `insert returns the new row id`() {
        fun tuning(name: String) = SqlValues().apply {
            put("name", name); put("grp", "CUSTOM"); put("strings", "E2 A2 D3 G3 B3 E4"); put("string_count", 6)
        }
        val a = sql.insertOrThrow("tunings", null, tuning("Test"))
        val b = sql.insertOrThrow("tunings", null, tuning("Test 2"))
        assertTrue(a > 0)
        assertEquals(a + 1, b)
    }

    @Test
    fun `IGNORE on a clash returns -1 and keeps the old row`() {
        sql.insertOrThrow("settings", null, setting("k", "first"))
        assertEquals(-1L, sql.insertWithOnConflict("settings", null, setting("k", "second"), Conflict.IGNORE))
        val v = sql.rawQuery("SELECT value FROM settings WHERE key = 'k'", null).use { it.moveToFirst(); it.getString(0) }
        assertEquals("first", v)
    }

    @Test
    fun `REPLACE on a clash replaces the row`() {
        sql.insertOrThrow("settings", null, setting("k", "first"))
        assertTrue(sql.insertWithOnConflict("settings", null, setting("k", "second"), Conflict.REPLACE) > 0)
        val v = sql.rawQuery("SELECT value FROM settings WHERE key = 'k'", null).use { it.moveToFirst(); it.getString(0) }
        assertEquals("second", v)
    }

    @Test
    fun `insert swallows a constraint failure, insertOrThrow does not`() {
        sql.insertOrThrow("settings", null, setting("k", "v"))
        assertEquals(-1L, sql.insert("settings", null, setting("k", "v")))
        try {
            sql.insertOrThrow("settings", null, setting("k", "v"))
            fail("expected a constraint failure")
        } catch (_: Exception) {
        }
    }

    @Test
    fun `update and delete report rows changed`() {
        sql.insertOrThrow("settings", null, setting("a", "1"))
        sql.insertOrThrow("settings", null, setting("b", "1"))
        assertEquals(2, sql.update("settings", SqlValues().apply { put("value", "2") }, "value = ?", arrayOf("1")))
        assertEquals(1, sql.delete("settings", "key = ?", arrayOf("a")))
        assertEquals(1, sql.executeUpdateDelete("UPDATE settings SET value = value || '!' WHERE key = ?", arrayOf("b")))
    }

    @Test
    fun `NULL reads as null text and zero numbers`() {
        sql.rawQuery("SELECT NULL, NULL", null).use { c ->
            assertTrue(c.moveToFirst())
            assertTrue(c.isNull(0))
            assertNull(c.getString(0))
            assertEquals(0L, c.getLong(1))
        }
    }

    @Test
    fun `numbers read as text the way Android prints them`() {
        sql.rawQuery("SELECT 5, 2.5, 'x'", null).use { c ->
            c.moveToFirst()
            assertEquals("5", c.getString(0))
            assertEquals("2.5", c.getString(1))
            assertEquals(5, c.getInt(0))
            assertEquals(2.5, c.getDouble(1), 0.0)
            assertEquals(listOf("5", "2.5", "'x'"), c.columnNames.toList())
        }
    }

    @Test
    fun `a cursor can go back to the first row and knows its count`() {
        sql.insertOrThrow("settings", null, setting("a", "1"))
        sql.insertOrThrow("settings", null, setting("b", "2"))
        sql.rawQuery("SELECT key FROM settings ORDER BY key", null).use { c ->
            assertEquals(2, c.count)
            while (c.moveToNext()) Unit
            assertFalse(c.moveToNext())
            assertTrue(c.moveToFirst())
            assertEquals("a", c.getString(0))
        }
    }

    @Test
    fun `a transaction that is not marked successful rolls back`() {
        sql.beginTransaction()
        try {
            sql.insertOrThrow("settings", null, setting("k", "v"))
        } finally {
            sql.endTransaction()
        }
        assertEquals(0, sql.rawQuery("SELECT COUNT(*) FROM settings", null).use { it.moveToFirst(); it.getInt(0) })
    }

    @Test
    fun `a failed inner transaction rolls back the outer one`() {
        sql.beginTransaction()
        try {
            sql.insertOrThrow("settings", null, setting("outer", "v"))
            sql.beginTransaction()
            try {
                sql.insertOrThrow("settings", null, setting("inner", "v"))
            } finally {
                sql.endTransaction()
            }
            sql.setTransactionSuccessful()
        } finally {
            sql.endTransaction()
        }
        assertEquals(0, sql.rawQuery("SELECT COUNT(*) FROM settings", null).use { it.moveToFirst(); it.getInt(0) })
    }

    @Test
    fun `a successful transaction commits`() {
        sql.beginTransaction()
        try {
            sql.insertOrThrow("settings", null, setting("k", "v"))
            sql.setTransactionSuccessful()
        } finally {
            sql.endTransaction()
        }
        assertEquals(1, sql.rawQuery("SELECT COUNT(*) FROM settings", null).use { it.moveToFirst(); it.getInt(0) })
    }

    @Test
    fun `foreign keys are enforced`() {
        assertEquals(-1L, sql.insert("session_items", null, SqlValues().apply {
            put("session_id", 999L); put("seconds", 60)
        }))
    }
}
