package com.riffbuddy

import com.riffbuddy.data.Rig
import com.riffbuddy.data.SavedAmp
import com.riffbuddy.data.SavedGuitar
import com.riffbuddy.domain.PlayerProfile
import com.riffbuddy.domain.Session
import com.riffbuddy.domain.SessionItem
import com.riffbuddy.domain.Song
import com.riffbuddy.domain.SongStatus
import com.riffbuddy.domain.gear.AmpKind
import com.riffbuddy.domain.gear.AmpProfile
import com.riffbuddy.domain.gear.GuitarProfile
import com.riffbuddy.ui.tone.CoachHome
import com.riffbuddy.ui.tone.StarterKind
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The words on the coach's welcome: greeting, what it knows, starters, and when a chat was. */
class CoachHomeTest {

    private val zone = TimeZone.UTC
    private val now = LocalDateTime(2026, 11, 23, 19, 30).toInstant(zone)
    private val guitar = SavedGuitar(1, "Tele", GuitarProfile("Maker", "Tele"))
    private val amp = SavedAmp(2, "Combo 20", AmpProfile("Maker", "Combo 20", AmpKind.MODELING, emptyList()))
    private val rig = Rig(3, "Home", guitar, amp, true)
    private fun song(id: Long, title: String, status: SongStatus = SongStatus.LEARNING, current: Int? = null, target: Int? = null, key: String? = null) =
        Song(id = id, title = title, status = status, currentBpm = current, targetBpm = target, key = key)
    private fun session(daysAgo: Int, songId: Long? = null) = Session(
        startedAt = now - daysAgo.days,
        endedAt = now - daysAgo.days + 20.minutes,
        items = listOfNotNull(songId?.let { SessionItem(songId = it, seconds = 600) }),
    )

    @Test
    fun `greeting follows the hour`() {
        assertEquals("Good morning", CoachHome.greeting(5))
        assertEquals("Good morning", CoachHome.greeting(11))
        assertEquals("Good afternoon", CoachHome.greeting(12))
        assertEquals("Good evening", CoachHome.greeting(21))
        assertTrue(CoachHome.greeting(23).startsWith("Up late"))
        assertTrue(CoachHome.greeting(2).startsWith("Up late"))
    }

    @Test
    fun `when a chat was, in words`() {
        assertEquals("Just now", CoachHome.whenAgo(now - 20.seconds, now, zone))
        assertEquals("12 min ago", CoachHome.whenAgo(now - 12.minutes, now, zone))
        assertEquals("Today, 3:30 PM", CoachHome.whenAgo(now - 4.hours, now, zone))
        assertEquals("Yesterday", CoachHome.whenAgo(now - 1.days, now, zone))
        assertEquals("4 days ago", CoachHome.whenAgo(now - 4.days, now, zone))
        assertEquals("Nov 2", CoachHome.whenAgo(now - 21.days, now, zone))
        assertEquals("Nov 23, 2025", CoachHome.whenAgo(now - 365.days, now, zone))
    }

    @Test
    fun `what the coach knows comes from the player's data`() {
        val songs = listOf(song(1, "First Song"), song(2, "Second Song"), song(3, "Old One", SongStatus.MASTERED))
        assertEquals(
            "I know your Tele into the Combo 20, the 2 songs you're learning, and that your last session was yesterday.",
            CoachHome.knows(rig, songs, listOf(session(1), session(5)), now, zone),
        )
        assertEquals(
            "I know your Combo 20 and that you're learning First Song.",
            CoachHome.knows(rig.copy(guitar = null), songs.take(1), emptyList(), now, zone),
        )
        assertEquals("I know that you've already practiced today.", CoachHome.knows(null, emptyList(), listOf(session(0)), now, zone))
        assertNull(CoachHome.knows(null, emptyList(), emptyList(), now, zone))
    }

    @Test
    fun `starters cover every kind before repeating one, and use the player's things`() {
        val songs = listOf(song(1, "Slow Burn", current = 90, target = 120, key = "E minor"))
        val starters = CoachHome.starters(rig, songs, listOf(session(5, songId = 1)), PlayerProfile(), now, zone, limit = 8)
        assertEquals(
            listOf(StarterKind.TECHNIQUE, StarterKind.TONE, StarterKind.THEORY, StarterKind.PRACTICE),
            starters.take(4).map { it.kind },
        )
        assertTrue(starters[0].question.contains("90 BPM") && starters[0].question.contains("120"))
        assertTrue(starters[1].question.contains("Slow Burn") && starters[1].question.contains("Combo 20"))
        assertTrue(starters[2].question.contains("E minor"))
        assertTrue(starters[3].question.contains("5 days"))
        assertEquals(8, starters.size)
        assertEquals(starters.size, starters.map { it.question }.distinct().size)
    }

    @Test
    fun `with nothing entered the starters still make sense`() {
        val starters = CoachHome.starters(null, emptyList(), emptyList(), PlayerProfile(level = PlayerProfile.Level.BEGINNER), now, zone, limit = 4)
        assertEquals(4, starters.size)
        assertTrue(starters.none { it.question.contains("null") })
        assertTrue(starters.any { it.question.contains("beginner") })
    }

    @Test
    fun `the focus song is the learning song played most recently`() {
        val songs = listOf(song(1, "A"), song(2, "B"), song(3, "C", SongStatus.WISHLIST))
        assertEquals(2L, CoachHome.focusSong(songs, listOf(session(3, 1), session(1, 2), session(0, 3)))?.id)
        assertEquals(1L, CoachHome.focusSong(songs, emptyList())?.id)
        assertNull(CoachHome.focusSong(listOf(song(3, "C", SongStatus.WISHLIST)), emptyList()))
    }
}
