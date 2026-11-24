package com.riffbuddy

import com.riffbuddy.ai.SongAssists
import com.riffbuddy.domain.Song
import com.riffbuddy.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SongAssistsTest {

    private val song = Song(id = 1, title = "Nothing Else Matters", artist = "Metallica")

    /** "Songs like this" never returns the song it was asked about. */
    @Test
    fun `the source song is filtered out of its own suggestions`() {
        val json = JSONObject(
            """
            {"suggestions":[
              {"title":"Nothing Else Matters","artist":"Metallica","reason":"same song"},
              {"title":"Fade to Black","artist":"Metallica","reason":"same era"}
            ]}
            """.trimIndent()
        )
        val results = SongAssists.parseSimilar(json, song)
        assertEquals(1, results.size)
        assertEquals("Fade to Black", results.first().title)
    }

    /** And filtered out even when it comes back punctuated differently. */
    @Test
    fun `the source song is caught despite punctuation and case`() {
        val journey = Song(id = 2, title = "Don't Stop Believin'", artist = "Journey")
        val json = JSONObject(
            """{"suggestions":[{"title":"dont stop believin","artist":"Journey","reason":"x"}]}"""
        )
        assertTrue(SongAssists.parseSimilar(json, journey).isEmpty())
    }

    @Test
    fun `duplicate suggestions are collapsed and the list is capped`() {
        val items = (1..12).joinToString(",") {
            """{"title":"Song $it","artist":"A","reason":"r"}"""
        }
        val withDupes = """{"suggestions":[$items,{"title":"Song 1","artist":"A","reason":"r"}]}"""
        val results = SongAssists.parseSimilar(JSONObject(withDupes), song)
        assertEquals(8, results.size)
        assertEquals(results.size, results.map { it.title }.distinct().size)
    }

    @Test
    fun `an unknown difficulty band falls back to similar`() {
        val json = JSONObject("""{"suggestions":[{"title":"X","artist":"Y","reason":"r","band":"brutal"}]}""")
        assertEquals("similar", SongAssists.parseSimilar(json, song).first().band)
    }

    /** A rating off the scale would draw a bar past the end of its track. */
    @Test
    fun `difficulty is clamped to one through ten`() {
        assertEquals(10, SongAssists.parseDifficulty(JSONObject("""{"difficulty":47}""")).rating)
        assertEquals(1, SongAssists.parseDifficulty(JSONObject("""{"difficulty":-3}""")).rating)
        assertEquals(5, SongAssists.parseDifficulty(JSONObject("{}")).rating)
    }

    /** Three steps. Ten is a plan nobody follows. */
    @Test
    fun `the approach is capped at three steps`() {
        val steps = (1..9).joinToString(",") { "\"step $it\"" }
        val parsed = SongAssists.parseDifficulty(JSONObject("""{"difficulty":5,"approach":[$steps]}"""))
        assertEquals(3, parsed.approach.size)
    }

    @Test
    fun `difficulty reasons survive and blanks are dropped`() {
        val parsed = SongAssists.parseDifficulty(
            JSONObject(
                """
                {"difficulty":7,"reasons":[
                  {"factor":"tempo","detail":"fast strumming"},
                  {"detail":"no factor given"}]}
                """.trimIndent()
            )
        )
        assertEquals(1, parsed.reasons.size)
        assertEquals("tempo", parsed.reasons.first().factor)
    }

    @Test
    fun `how to play parses sections and tolerates missing pieces`() {
        val parsed = SongAssists.parseHowToPlay(
            JSONObject(
                """
                {"overview":"Arpeggiated intro then power chords",
                 "sections":[{"name":"Intro","what_happens":"clean arpeggios","watch_out":"timing"},
                             {"what_happens":"no name so dropped"}],
                 "techniques":["fingerpicking",""],
                 "pitfalls":["rushing the intro"]}
                """.trimIndent()
            )
        )
        assertEquals(1, parsed.sections.size)
        assertEquals("Intro", parsed.sections.first().name)
        assertEquals(1, parsed.techniques.size)
        assertFalse(parsed.techniques.contains(""))
    }

    @Test
    fun `empty replies do not throw`() {
        assertTrue(SongAssists.parseSimilar(JSONObject("{}"), song).isEmpty())
        assertEquals("", SongAssists.parseHowToPlay(JSONObject("{}")).overview)
    }

    /** The schemas must constrain the fields the parsers depend on. */
    @Test
    fun `the difficulty schema bounds the rating`() {
        val rating = SongAssists.difficultySchema
            .getJSONObject("properties").getJSONObject("difficulty")
        assertEquals(1, rating.getInt("minimum"))
        assertEquals(10, rating.getInt("maximum"))
    }
}
