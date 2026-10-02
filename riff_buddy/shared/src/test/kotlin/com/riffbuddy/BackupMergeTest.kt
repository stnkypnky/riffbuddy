package com.riffbuddy

import com.riffbuddy.data.BackupMerge
import com.riffbuddy.data.MergeStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Add to what is here" between two devices, against an in-memory store. The
 * cases are the ones a tablet-to-phone routine produces.
 */
class BackupMergeTest {

    /** Tables as the schema has them: columns, key, foreign keys. */
    private class Fake : MergeStore {
        val schema = mapOf(
            "songs" to listOf("id", "title", "created_at", "updated_at"),
            "song_parts" to listOf("id", "song_id", "name", "created_at", "updated_at"),
            "sessions" to listOf("id", "started_at"),
            "session_items" to listOf("id", "session_id", "song_id", "seconds"),
            "tab_files" to listOf("id", "name", "sha256", "song_id", "created_at", "updated_at"),
            "settings" to listOf("key", "value"),
            "amps" to listOf("id", "name"),
        )
        val fks = mapOf(
            "song_parts" to listOf("song_id" to "songs"),
            "session_items" to listOf("session_id" to "sessions", "song_id" to "songs"),
            "tab_files" to listOf("song_id" to "songs"),
        )
        val rows = schema.keys.associateWith { mutableListOf<MutableMap<String, String?>>() }
        private val next = mutableMapOf<String, Int>()

        override fun columns(table: String) = schema.getValue(table).toSet()
        override fun primaryKey(table: String) = if (table == "settings") listOf("key") else listOf("id")
        override fun foreignKeys(table: String) = fks[table].orEmpty()
        override fun find(table: String, where: Map<String, String>) =
            rows.getValue(table).firstOrNull { r -> where.all { (k, v) -> r[k] == v } }
        override fun insert(table: String, values: Map<String, String?>, keepId: Boolean): String? {
            val row = values.toMutableMap()
            if (primaryKey(table) == listOf("id")) {
                if (!keepId) row["id"] = ((rows.getValue(table).maxOfOrNull { it["id"]!!.toInt() } ?: 0) + 1).toString()
                if (find(table, mapOf("id" to row["id"]!!)) != null) return null
            }
            rows.getValue(table) += row
            return row["id"] ?: row["key"]
        }
        override fun update(table: String, id: String, values: Map<String, String?>) {
            find(table, mapOf("id" to id))!!.putAll(values)
        }
        fun add(table: String, vararg p: Pair<String, String?>) { rows.getValue(table) += mutableMapOf(*p) }
        fun get(table: String, id: String) = find(table, mapOf("id" to id))
    }

    private fun backup(vararg t: Pair<String, List<Map<String, String?>>>) = t.toList()

    @Test
    fun `a song added on each device ends up on both, and its parts follow it`() {
        val phone = Fake()
        phone.add("songs", "id" to "9", "title" to "Eruption Solo", "created_at" to "100", "updated_at" to "100")
        val result = BackupMerge(phone).merge(backup(
            "songs" to listOf(mapOf("id" to "9", "title" to "Master of Puppets", "created_at" to "200", "updated_at" to "200")),
            "song_parts" to listOf(mapOf("id" to "99", "song_id" to "9", "name" to "Intro", "created_at" to "201", "updated_at" to "201")),
        ))
        assertEquals(listOf("Eruption Solo", "Master of Puppets"), phone.rows["songs"]!!.map { it["title"] })
        val puppets = phone.rows["songs"]!!.first { it["title"] == "Master of Puppets" }["id"]
        assertEquals("the part points at the renumbered song", puppets, phone.rows["song_parts"]!!.single()["song_id"])
        assertEquals(1, result.renumbered)
    }

    @Test
    fun `merging the same backup twice changes nothing the second time`() {
        val phone = Fake()
        phone.add("songs", "id" to "9", "title" to "Eruption Solo", "created_at" to "100", "updated_at" to "100")
        val b = backup(
            "songs" to listOf(mapOf("id" to "9", "title" to "Master of Puppets", "created_at" to "200", "updated_at" to "200")),
            "song_parts" to listOf(mapOf("id" to "99", "song_id" to "9", "name" to "Intro", "created_at" to "201", "updated_at" to "201")),
        )
        BackupMerge(phone).merge(b)
        val second = BackupMerge(phone).merge(b)
        assertEquals(2, phone.rows["songs"]!!.size)
        assertEquals(1, phone.rows["song_parts"]!!.size)
        assertEquals(0, second.added.values.sum())
    }

    @Test
    fun `an edit made on the other device arrives, and a newer one here is kept`() {
        val phone = Fake()
        phone.add("songs", "id" to "1", "title" to "Floods", "created_at" to "10", "updated_at" to "50")
        phone.add("songs", "id" to "3", "title" to "Am I Evil? (mine)", "created_at" to "30", "updated_at" to "90")
        val result = BackupMerge(phone).merge(backup("songs" to listOf(
            mapOf("id" to "1", "title" to "Floods (Outro)", "created_at" to "10", "updated_at" to "60"),
            mapOf("id" to "3", "title" to "Am I Evil?", "created_at" to "30", "updated_at" to "80"),
        )))
        assertEquals("Floods (Outro)", phone.get("songs", "1")!!["title"])
        assertEquals("Am I Evil? (mine)", phone.get("songs", "3")!!["title"])
        assertEquals(1, result.updated)
    }

    @Test
    fun `a new session's items come with it even when their ids are taken here`() {
        val phone = Fake()
        phone.add("sessions", "id" to "20", "started_at" to "1000")
        phone.add("session_items", "id" to "19", "session_id" to "20", "seconds" to "60")
        BackupMerge(phone).merge(backup(
            "sessions" to listOf(mapOf("id" to "20", "started_at" to "2000")),
            "session_items" to listOf(mapOf("id" to "19", "session_id" to "20", "seconds" to "600")),
        ))
        assertEquals(2, phone.rows["sessions"]!!.size)
        val newSession = phone.rows["sessions"]!!.first { it["started_at"] == "2000" }["id"]
        val items = phone.rows["session_items"]!!
        assertEquals(2, items.size)
        assertEquals("the phone's own item is untouched", "20", phone.get("session_items", "19")!!["session_id"])
        assertEquals(newSession, items.first { it["seconds"] == "600" }["session_id"])
    }

    @Test
    fun `the same tab file imported on each device is one tab, found by its contents`() {
        val phone = Fake()
        phone.add("tab_files", "id" to "5", "name" to "Floods", "sha256" to "abc", "created_at" to "1", "updated_at" to "1")
        BackupMerge(phone).merge(backup("tab_files" to listOf(
            mapOf("id" to "7", "name" to "Floods", "sha256" to "abc", "created_at" to "2", "updated_at" to "2"),
        )))
        assertEquals(1, phone.rows["tab_files"]!!.size)
    }

    @Test
    fun `settings and gear already here are kept, new ones added`() {
        val phone = Fake()
        phone.add("settings", "key" to "a4_hz", "value" to "440")
        phone.add("amps", "id" to "1", "name" to "LT25")
        BackupMerge(phone).merge(backup(
            "settings" to listOf(mapOf("key" to "a4_hz", "value" to "442"), mapOf("key" to "tab_only", "value" to "true")),
            "amps" to listOf(mapOf("id" to "1", "name" to "Katana"), mapOf("id" to "2", "name" to "Spark")),
        ))
        assertEquals("440", phone.find("settings", mapOf("key" to "a4_hz"))!!["value"])
        assertTrue(phone.find("settings", mapOf("key" to "tab_only")) != null)
        assertEquals(listOf("LT25", "Spark"), phone.rows["amps"]!!.map { it["name"] })
    }
}
