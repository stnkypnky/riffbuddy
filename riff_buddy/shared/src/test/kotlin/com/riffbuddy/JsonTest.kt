package com.riffbuddy

import com.riffbuddy.json.JSONArray
import com.riffbuddy.json.JSONException
import com.riffbuddy.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's own JSON objects stand in for org.json on every edition. Backups
 * and sync messages pass between devices running different versions, so
 * what they write must read the same in org.json, and the other way round.
 * The real org.json is on the test classpath as the reference.
 */
class JsonTest {

    private val sample = """
        {"text":"Floods — \"live\" \\ </script> é 🎸 tab\tline\nend",
         "int":42,"negative":-7,"long":1794083373078,"double":0.1,"whole":3.0,"exp":1.5E-7,
         "yes":true,"no":false,"nothing":null,
         "rows":[{"id":"1","title":"Song","note":null},{"id":"2","title":"","note":"x"}],
         "nested":{"empty":{},"list":[],"mixed":[1,"two",3.5,null,true,[2]]}}
    """.trimIndent()

    @Test
    fun `what this writes, org json reads as the same document`() {
        val mine = JSONObject(sample)
        val reference = org.json.JSONObject(sample)
        assertEquals(plain(reference), plain(org.json.JSONObject(mine.toString())))
    }

    @Test
    fun `what org json writes, this reads as the same document`() {
        val written = org.json.JSONObject(sample).toString()
        val mine = JSONObject(written)
        assertEquals(plain(org.json.JSONObject(sample)), plain(org.json.JSONObject(mine.toString())))
    }

    /** An org.json value as plain maps, lists and doubles, to compare by value. */
    private fun plain(v: Any?): Any? = when (v) {
        is org.json.JSONObject -> v.keys().asSequence().associateWith { plain(v.get(it)) }
        is org.json.JSONArray -> (0 until v.length()).map { plain(v.get(it)) }
        is Number -> v.toDouble()
        org.json.JSONObject.NULL -> null
        else -> v
    }

    @Test
    fun `numbers are written as org json writes them`() {
        listOf<Any>(0, 42, -7, 1794083373078L, 0.1, 3.0, 1.5, -2.25, 120.0, 1e-7, 1e20).forEach { n ->
            assertEquals("$n", org.json.JSONArray().put(n).toString(), JSONArray().put(n).toString())
        }
    }

    @Test
    fun `keys keep the order they were put in`() {
        val o = JSONObject().put("b", 1).put("a", 2).put("c", 3)
        assertEquals("""{"b":1,"a":2,"c":3}""", o.toString())
        assertEquals(listOf("b", "a", "c"), o.keys().asSequence().toList())
    }

    @Test
    fun `getters read what was parsed`() {
        val o = JSONObject(sample)
        assertEquals(42, o.getInt("int"))
        assertEquals(1794083373078L, o.getLong("long"))
        assertEquals(0.1, o.getDouble("double"), 0.0)
        assertEquals(3, o.optInt("whole"))
        assertTrue(o.getBoolean("yes"))
        assertEquals("Song", o.getJSONArray("rows").getJSONObject(0).getString("title"))
        assertEquals(6, o.getJSONObject("nested").getJSONArray("mixed").length())
        assertEquals("two", o.getJSONObject("nested").getJSONArray("mixed").getString(1))
    }

    @Test
    fun `missing and null values fall back`() {
        val o = JSONObject(sample)
        assertEquals("", o.optString("absent"))
        assertEquals("fallback", o.optString("nothing", "fallback"))
        assertEquals(5, o.optInt("absent", 5))
        assertEquals(9L, o.optLong("nothing", 9L))
        assertTrue(o.optDouble("absent").isNaN())
        assertFalse(o.optBoolean("absent"))
        assertNull(o.optJSONObject("text"))
        assertNull(o.optJSONArray("absent"))
        assertTrue(o.isNull("nothing"))
        assertTrue(o.isNull("absent"))
        assertTrue(o.has("nothing"))
        assertFalse(o.has("absent"))
    }

    @Test
    fun `numbers and text convert as org json converts them`() {
        val o = JSONObject().put("n", "12").put("b", "true").put("i", 7)
        assertEquals(12, o.optInt("n"))
        assertTrue(o.optBoolean("b"))
        assertEquals("7", o.optString("i"))
        assertEquals("7", o.getString("i"))
    }

    @Test
    fun `putting null removes the key, putting NULL keeps it`() {
        val o = JSONObject().put("a", 1).put("b", 2)
        o.put("a", null)
        o.put("b", JSONObject.NULL)
        assertEquals("""{"b":null}""", o.toString())
    }

    @Test
    fun `arrays are built from lists and arrays`() {
        assertEquals("""["a","b"]""", JSONArray(listOf("a", "b")).toString())
        assertEquals("""["x",1]""", JSONArray(arrayOf<Any>("x", 1)).toString())
    }

    @Test(expected = JSONException::class)
    fun `a missing key is an error for get`() {
        JSONObject("{}").getString("absent")
    }

    @Test(expected = JSONException::class)
    fun `bad text is an error`() {
        JSONObject("{\"unclosed\": ")
    }

    @Test(expected = JSONException::class)
    fun `an array is not an object`() {
        JSONObject("[1,2]")
    }
}
