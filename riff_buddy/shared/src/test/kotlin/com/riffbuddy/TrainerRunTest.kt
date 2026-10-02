package com.riffbuddy

import com.riffbuddy.audio.TimeSignature
import com.riffbuddy.audio.TrainerRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** A trainer run offered to the log, and a song's time signature read. */
class TrainerRunTest {

    private val t0 = Instant.parse("2026-09-21T18:00:00Z")

    @Test
    fun `a run logs its start, the fastest it reached, and its length`() {
        val run = TrainerRun(startBpm = 60, reachedBpm = 60, startedAt = t0, songId = 7)
            .seen(65).seen(70).seen(65)          // a "Repeat" dropped back
        val item = run.toItem(t0.plusSeconds(180))
        assertEquals(60, item.bpmStart)
        assertEquals(70, item.bpmEnd)
        assertEquals(180, item.seconds)
        assertEquals(7L, item.songId)
        assertEquals("Speed trainer", item.exerciseName)
    }

    @Test
    fun `a start pressed and stopped again is not offered`() {
        val run = TrainerRun(60, 60, t0)
        assertFalse(run.worthOffering(t0.plusSeconds(5)))
        assertTrue(run.worthOffering(t0.plusSeconds(15)))
    }

    @Test
    fun `song time signatures parse, and nonsense does not`() {
        assertEquals(TimeSignature(6, 8), TimeSignature.parse("6/8"))
        assertEquals(TimeSignature(7, 8), TimeSignature.parse(" 7 / 8 "))
        assertNull(TimeSignature.parse("4/2"))
        assertNull(TimeSignature.parse("common"))
        assertNull(TimeSignature.parse(null))
        assertNull(TimeSignature.parse("17/4"))
    }
}
