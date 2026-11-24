package com.riffbuddy

import com.riffbuddy.domain.EPOCH
import com.riffbuddy.domain.Session
import com.riffbuddy.domain.SessionItem
import com.riffbuddy.domain.SessionTag
import com.riffbuddy.domain.Tagging
import org.junit.Assert.assertEquals
import org.junit.Test

/** A session's minutes shared across what it was spent on. */
class TaggingTest {

    @Test
    fun `minutes split evenly and always add back up`() {
        assertEquals(listOf(10, 10, 10), Tagging.split(30, 3))
        assertEquals(listOf(11, 10, 10), Tagging.split(31, 3))
        assertEquals(listOf(1, 0, 0), Tagging.split(1, 3))
        assertEquals(emptyList<Int>(), Tagging.split(30, 0))
        (1..60).forEach { total -> (1..7).forEach { n -> assertEquals(total, Tagging.split(total, n).sum()) } }
    }

    @Test
    fun `only tag items are read back as tags`() {
        val s = Session(
            startedAt = EPOCH,
            items = listOf(
                SessionItem(songId = 1, seconds = 600, notes = Tagging.MARK, partId = 9),
                SessionItem(songId = 2, seconds = 0),                         // the Practice button
                SessionItem(exerciseName = "Chromatic", bpmEnd = 80),         // the speed trainer
            ),
        )
        assertEquals(listOf(SessionTag(1, 9, 600)), Tagging.of(s))
    }
}
