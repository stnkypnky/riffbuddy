package com.riffbuddy

import com.riffbuddy.audio.AtTarget
import com.riffbuddy.audio.SpeedTrainer
import com.riffbuddy.audio.TrainerConfig
import com.riffbuddy.audio.TrainerMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedTrainerTest {

    /** A 60 to 90 BPM ramp with step 5 every 4 bars changes tempo exactly on
     *  bar boundaries. */
    @Test
    fun `ramp steps on bar boundaries only`() {
        var s = SpeedTrainer.start(TrainerConfig(startBpm = 60, targetBpm = 90, step = 5, everyBars = 4))
        assertEquals(60, s.currentBpm)
        repeat(3) { s = SpeedTrainer.onBarComplete(s) }
        assertEquals("must not step before the fourth bar", 60, s.currentBpm)
        s = SpeedTrainer.onBarComplete(s)
        assertEquals(65, s.currentBpm)
        assertEquals(0, s.barsAtThisTempo)
    }

    @Test
    fun `ramp reaches the target exactly and holds`() {
        var s = SpeedTrainer.start(TrainerConfig(startBpm = 60, targetBpm = 90, step = 5, everyBars = 4))
        repeat(4 * 6) { s = SpeedTrainer.onBarComplete(s) }
        assertEquals(90, s.currentBpm)
        assertTrue(s.reachedTarget)
        assertFalse(s.shouldStop)
        repeat(8) { s = SpeedTrainer.onBarComplete(s) }
        assertEquals("HOLD must stay at the target", 90, s.currentBpm)
    }

    /** A step that does not divide the span must not overshoot. */
    @Test
    fun `an uneven step clamps to the target instead of overshooting`() {
        var s = SpeedTrainer.start(TrainerConfig(startBpm = 60, targetBpm = 90, step = 4, everyBars = 1))
        repeat(20) { s = SpeedTrainer.onBarComplete(s) }
        assertEquals("should land on 90, not 92", 90, s.currentBpm)
    }

    @Test
    fun `stop at target sets shouldStop`() {
        var s = SpeedTrainer.start(
            TrainerConfig(startBpm = 80, targetBpm = 90, step = 5, everyBars = 1, atTarget = AtTarget.STOP)
        )
        s = SpeedTrainer.onBarComplete(s)
        assertFalse(s.shouldStop)
        s = SpeedTrainer.onBarComplete(s)
        assertTrue(s.shouldStop)
        assertEquals(90, s.currentBpm)
    }

    @Test
    fun `manual mode ignores bars and steps only on confirm`() {
        var s = SpeedTrainer.start(
            TrainerConfig(mode = TrainerMode.MANUAL_CONFIRM, startBpm = 70, targetBpm = 100, step = 10)
        )
        repeat(20) { s = SpeedTrainer.onBarComplete(s) }
        assertEquals(70, s.currentBpm)
        s = SpeedTrainer.onNailedIt(s)
        assertEquals(80, s.currentBpm)
        s = SpeedTrainer.onRepeat(s)
        assertEquals("repeat holds the tempo", 80, s.currentBpm)
        assertEquals(0, s.barsAtThisTempo)
    }

    /** Ramping down is a legitimate way to practice, and must also clamp. */
    @Test
    fun `a descending ramp works`() {
        var s = SpeedTrainer.start(TrainerConfig(startBpm = 120, targetBpm = 100, step = 7, everyBars = 1))
        repeat(10) { s = SpeedTrainer.onBarComplete(s) }
        assertEquals(100, s.currentBpm)
    }

    @Test
    fun `progress runs from zero to one`() {
        var s = SpeedTrainer.start(TrainerConfig(startBpm = 60, targetBpm = 100, step = 10, everyBars = 1))
        assertEquals(0f, s.progress, 0.001f)
        repeat(2) { s = SpeedTrainer.onBarComplete(s) }
        assertEquals(0.5f, s.progress, 0.001f)
        repeat(2) { s = SpeedTrainer.onBarComplete(s) }
        assertEquals(1f, s.progress, 0.001f)
    }
}
