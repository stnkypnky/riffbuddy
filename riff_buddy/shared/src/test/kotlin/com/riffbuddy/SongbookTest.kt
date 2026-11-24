package com.riffbuddy

import com.riffbuddy.domain.Song
import com.riffbuddy.domain.SongSort
import com.riffbuddy.domain.SongStatus
import com.riffbuddy.domain.Songbook
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SongbookTest {

    private fun song(
        id: Long,
        title: String,
        artist: String = "",
        status: SongStatus = SongStatus.LEARNING,
        difficulty: Int? = null,
        tags: String = "",
        added: Long = 0,
    ) = Song(
        id = id, title = title, artist = artist, status = status,
        selfDifficulty = difficulty, tags = tags,
        createdAt = Instant.fromEpochSeconds(added), updatedAt = Instant.fromEpochSeconds(added),
    )

    private val library = listOf(
        song(1, "Nothing Else Matters", "Metallica", difficulty = 3, tags = "clean, ballad", added = 100),
        song(2, "Enter Sandman", "Metallica", difficulty = 2, tags = "riff", added = 200),
        song(3, "Blackbird", "The Beatles", SongStatus.WISHLIST, tags = "fingerstyle", added = 300),
        song(4, "Sultans of Swing", "Dire Straits", difficulty = 5, added = 400),
    )

    // ---- duplicates --------------------------------------------------------

    @Test
    fun `the same song in different case is a duplicate`() {
        assertNotNull(Songbook.findDuplicate(library, "nothing else matters", "METALLICA"))
    }

    /** Apostrophes and stray punctuation are exactly what people type differently. */
    @Test
    fun `punctuation does not hide a duplicate`() {
        val list = listOf(song(1, "Don't Stop Believin'", "Journey"))
        assertNotNull(Songbook.findDuplicate(list, "Dont Stop Believin", "Journey"))
        assertNotNull(Songbook.findDuplicate(list, "  Don't   Stop  Believin'  ", "journey"))
    }

    @Test
    fun `same title by a different artist is not a duplicate`() {
        assertNull(Songbook.findDuplicate(library, "Blackbird", "Alter Bridge"))
    }

    /** Editing a song must not flag it as a duplicate of itself. */
    @Test
    fun `a song is not its own duplicate when edited`() {
        assertNull(Songbook.findDuplicate(library, "Blackbird", "The Beatles", ignoreId = 3))
        assertNotNull(Songbook.findDuplicate(library, "Blackbird", "The Beatles", ignoreId = 99))
    }

    @Test
    fun `an empty title is never a duplicate`() {
        assertNull(Songbook.findDuplicate(library, "   ", "Metallica"))
    }

    // ---- search ------------------------------------------------------------

    @Test
    fun `search covers title, artist and tags`() {
        assertEquals(2, Songbook.filterAndSort(library, query = "metallica").size)
        assertEquals(1, Songbook.filterAndSort(library, query = "sandman").size)
        assertEquals(1, Songbook.filterAndSort(library, query = "fingerstyle").size)
    }

    @Test
    fun `an empty search returns everything`() {
        assertEquals(library.size, Songbook.filterAndSort(library, query = "   ").size)
    }

    @Test
    fun `filters combine with search`() {
        val result = Songbook.filterAndSort(library, query = "", status = SongStatus.WISHLIST)
        assertEquals(1, result.size)
        assertEquals("Blackbird", result.first().title)
    }

    // ---- sorting -----------------------------------------------------------

    @Test
    fun `title sort ignores case and punctuation`() {
        val titles = Songbook.filterAndSort(library, sort = SongSort.TITLE).map { it.title }
        assertEquals(listOf("Blackbird", "Enter Sandman", "Nothing Else Matters", "Sultans of Swing"), titles)
    }

    /**
     * An unrated song is "not yet judged", not "easy". Sorting it as zero would
     * bury the songs actually being worked on underneath everything untouched.
     */
    @Test
    fun `unrated songs sort last by difficulty, not first`() {
        val order = Songbook.filterAndSort(library, sort = SongSort.DIFFICULTY).map { it.title }
        assertEquals("Sultans of Swing", order.first())
        assertEquals("Blackbird", order.last())
    }

    @Test
    fun `recent sort uses last practiced, falling back to last edited`() {
        val practiced = mapOf(2L to Instant.fromEpochSeconds(9_000))
        val order = Songbook.filterAndSort(library, sort = SongSort.RECENT, lastPracticed = practiced)
        assertEquals("Enter Sandman", order.first().title)
        // The rest keep their edited order, newest first.
        assertEquals("Sultans of Swing", order[1].title)
    }

    /** Search must stay under 200 ms at 500 songs. */
    @Test
    fun `searching five hundred songs is fast`() {
        val many = (1..500).map { song(it.toLong(), "Song number $it", "Artist ${it % 40}", tags = "tag${it % 7}") }
        val started = System.nanoTime()
        repeat(20) { Songbook.filterAndSort(many, query = "artist 12", sort = SongSort.TITLE) }
        val millisPerSearch = (System.nanoTime() - started) / 1_000_000.0 / 20
        assertTrue("took %.1f ms per search".format(millisPerSearch), millisPerSearch < 200)
    }
}
