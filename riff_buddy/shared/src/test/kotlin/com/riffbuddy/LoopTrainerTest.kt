package com.riffbuddy

import com.riffbuddy.audio.LoopTrainer
import com.riffbuddy.audio.LoopTrainerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopTrainerTest {

    /**
     * The criterion: a configured trainer (60 % to 100 %, +5 % every 3 loops)
     * runs unattended and stops at target.
     */
    @Test
    fun `60 to 100 in fives every three loops runs unattended and stops after playing 100`() {
        var s = LoopTrainer.start(LoopTrainerConfig(60, 100, 5, 3))
        val speedsHeard = mutableListOf<Int>()
        var loops = 0
        while (!s.done && loops < 1000) {
            speedsHeard += s.percent
            s = LoopTrainer.onLoop(s)
            loops++
        }
        assertTrue(s.done)
        assertEquals(100, s.percent)
        // Nine speeds (60, 65 … 100), three loops each — including 100.
        assertEquals(27, loops)
        assertEquals(listOf(60, 65, 70, 75, 80, 85, 90, 95, 100), speedsHeard.distinct())
        assertEquals(3, speedsHeard.count { it == 100 })
    }

    @Test
    fun `the last step is clamped to the target, never past it`() {
        var s = LoopTrainer.start(LoopTrainerConfig(60, 98, 5, 1))
        val seen = mutableListOf<Int>()
        while (!s.done) { seen += s.percent; s = LoopTrainer.onLoop(s) }
        assertEquals(98, seen.last())
        assertTrue(seen.all { it <= 98 })
    }

    @Test
    fun `in manual mode loops are counted but only a clean run steps`() {
        var s = LoopTrainer.start(LoopTrainerConfig(60, 70, 5, 3, manual = true))
        repeat(10) { s = LoopTrainer.onLoop(s) }
        assertEquals(60, s.percent)
        assertEquals(10, s.totalLoops)
        s = LoopTrainer.onCleanRun(s)
        assertEquals(65, s.percent)
        s = LoopTrainer.onCleanRun(s)
        s = LoopTrainer.onCleanRun(s)
        assertTrue(s.done)
    }

    @Test
    fun `once done, it stays done`() {
        var s = LoopTrainer.start(LoopTrainerConfig(90, 95, 5, 1))
        repeat(5) { s = LoopTrainer.onLoop(s) }
        assertTrue(s.done)
        val after = LoopTrainer.onLoop(s)
        assertEquals(s, after)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a target slower than the start is refused`() {
        LoopTrainerConfig(startPercent = 100, targetPercent = 60)
    }

    /** The result goes into the session log, in BPM when the tempo is known. */
    @Test
    fun `the log line is in BPM when the recording has a tempo`() {
        val item = LoopTrainer.logItem("Solo run", songId = 3, seconds = 540, startPercent = 60, endPercent = 100, loops = 27, songBpm = 120)
        assertEquals("Loop: Solo run", item.exerciseName)
        assertEquals(72, item.bpmStart)
        assertEquals(120, item.bpmEnd)
        assertEquals("60% → 100%, 27 loops", item.notes)
        assertEquals(3L, item.songId)
    }

    @Test
    fun `without a tempo the log keeps percentages and no BPM`() {
        val item = LoopTrainer.logItem("A–B", songId = null, seconds = 60, startPercent = 50, endPercent = 70, loops = 6, songBpm = null)
        assertEquals(null, item.bpmStart)
        assertFalse(item.notes.isBlank())
    }
}
