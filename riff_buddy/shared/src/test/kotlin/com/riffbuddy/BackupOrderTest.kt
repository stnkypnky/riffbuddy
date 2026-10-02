package com.riffbuddy

import com.riffbuddy.data.BackupFormat
import com.riffbuddy.data.Schema
import org.junit.Assert.assertEquals
import org.junit.Test
import java.sql.DriverManager

/**
 * A restore inserts tables in [BackupFormat.TABLES] order with foreign keys on,
 * so a table listed before one it points at fails the whole restore
 * ("FOREIGN KEY constraint failed", code 787). That happened once: v8 gave
 * session_items a part_id, and song_parts was listed after it. This reads the
 * real foreign keys from the real schema, so a new reference can't slip by.
 */
class BackupOrderTest {
    @Test
    fun `every table is restored after the tables it points at`() {
        val order = BackupFormat.TABLES
        val wrong = mutableListOf<String>()
        DriverManager.getConnection("jdbc:sqlite::memory:").use { conn ->
            conn.createStatement().use { st -> Schema.fresh().forEach { st.execute(it) } }
            order.forEachIndexed { i, table ->
                conn.createStatement().executeQuery("PRAGMA foreign_key_list($table)").use { rs ->
                    while (rs.next()) {
                        val parent = rs.getString("table")
                        // A table pointing at itself is fine; an excluded one is never restored.
                        if (parent == table || parent !in order) continue
                        if (order.indexOf(parent) > i) wrong += "$table → $parent"
                    }
                }
            }
        }
        assertEquals("restored before the table they reference", emptyList<String>(), wrong)
    }
}
