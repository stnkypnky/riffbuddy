package com.riffbuddy

import com.riffbuddy.domain.Journey
import com.riffbuddy.domain.Mastery
import com.riffbuddy.domain.PartStage
import com.riffbuddy.domain.Session
import com.riffbuddy.domain.SessionItem
import com.riffbuddy.domain.Song
import com.riffbuddy.domain.SongPart
import com.riffbuddy.domain.SongStatus
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stages, a song's progress from its parts, and the story told when it's mastered. */
class MasteryTest {

    private fun part(stage: PartStage) = SongPart(songId = 1, name = "x", stage = stage)

    @Test
    fun `a tap moves a part on and mastered stays mastered`() {
        assertEquals(PartStage.LEARNING, PartStage.NOT_STARTED.next())
        assertEquals(PartStage.MASTERED, PartStage.UP_TO_SPEED.next())
        assertEquals(PartStage.MASTERED, PartStage.MASTERED.next())
    }

    @Test
    fun `progress is the average stage and needs parts`() {
        assertNull(Mastery.progress(emptyList()))
        assertEquals(0f, Mastery.progress(listOf(part(PartStage.NOT_STARTED)))!!, 0.001f)
        assertEquals(0.5f, Mastery.progress(listOf(part(PartStage.NOT_STARTED), part(PartStage.MASTERED)))!!, 0.001f)
        assertEquals(0.5f, Mastery.progress(listOf(part(PartStage.CLEAN_SLOW)))!!, 0.001f)
    }

    @Test
    fun `all mastered means every part and at least one`() {
        assertFalse(Mastery.allMastered(emptyList()))
        assertFalse(Mastery.allMastered(listOf(part(PartStage.MASTERED), part(PartStage.UP_TO_SPEED))))
        assertTrue(Mastery.allMastered(listOf(part(PartStage.MASTERED), part(PartStage.MASTERED))))
    }

    @Test
    fun `old backups saying retired read as mastered`() {
        assertEquals(SongStatus.MASTERED, SongStatus.fromName("RETIRED"))
        assertEquals(SongStatus.MASTERED, SongStatus.fromName("retired"))
        assertEquals(SongStatus.MASTERED, SongStatus.fromName("MASTERED"))
    }

    @Test
    fun `the journey counts only this song`() {
        val t0 = Instant.parse("2026-08-01T18:00:00Z")
        val song = Song(id = 7, title = "Floods", createdAt = t0)
        val sessions = listOf(
            Session(id = 1, startedAt = (t0 + (86_400).seconds), endedAt = (t0 + (86_400 + 1800).seconds),
                items = listOf(SessionItem(songId = 7, seconds = 900, bpmEnd = 70), SessionItem(songId = 8, seconds = 900))),
            Session(id = 2, startedAt = (t0 + (10 * 86_400).seconds), endedAt = (t0 + (10 * 86_400 + 600).seconds),
                items = listOf(SessionItem(songId = 7, seconds = 600, bpmEnd = 92))),
            Session(id = 3, startedAt = (t0 + (11 * 86_400).seconds), endedAt = (t0 + (11 * 86_400 + 600).seconds),
                items = listOf(SessionItem(songId = 8, seconds = 600))),
        )
        val j = Journey.of(song, sessions, now = t0.plus(21.days))
        assertEquals(21, j.days)
        assertEquals(1500, j.seconds)
        assertEquals(2, j.sessions)
        assertEquals(70, j.firstBpm)
        assertEquals(92, j.bestBpm)
    }

    @Test
    fun `a name the song already has gets the next number`() {
        assertEquals("Solo", Mastery.uniqueName("Solo", listOf("Intro", "Verse")))
        assertEquals("Verse 2", Mastery.uniqueName("Verse", listOf("Intro", "verse")))
        assertEquals("Verse 3", Mastery.uniqueName("Verse", listOf("Verse", "Verse 2")))
        // Players number their own: "Solo 1" alone doesn't make "Solo" taken.
        assertEquals("Solo", Mastery.uniqueName("Solo", listOf("Solo 1")))
    }

    @Test
    fun `the parts list has no duplicates`() {
        assertEquals(Mastery.COMMON_PARTS.size, Mastery.COMMON_PARTS.map { it.lowercase() }.toSet().size)
    }
}
