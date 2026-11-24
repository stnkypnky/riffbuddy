package com.riffbuddy

import com.riffbuddy.audio.AudioOutputs
import com.riffbuddy.audio.FrameTime
import com.riffbuddy.audio.MetronomeEngine
import com.riffbuddy.audio.MetronomeSettings
import com.riffbuddy.audio.PcmOutput
import com.riffbuddy.audio.StreamKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * The metronome's whole stream, as the speaker would get it: every click must
 * start at exactly its own sample, however the render loop is driven. A
 * stand-in output records what is written, so the spacing is measured in
 * samples rather than judged by ear.
 */
class MetronomeStreamTest {

    private class Recording(private val wanted: Int) : PcmOutput, AudioOutputs {
        val samples = ShortArray(wanted)
        var filled = 0
        val done = CountDownLatch(1)
        /** What to do once this many frames have been written; stands in for the player tapping a control. */
        var at: Pair<Int, () -> Unit>? = null

        override val sampleRate = 48_000
        override val bufferFrames = 1024
        override suspend fun write(pcm: ShortArray, offset: Int, frames: Int): Int {
            val n = minOf(frames, wanted - filled)
            pcm.copyInto(samples, filled, offset, offset + n)
            filled += n
            at?.let { (frame, action) -> if (filled >= frame) { at = null; action() } }
            if (filled >= wanted) done.countDown()
            return frames
        }
        override fun play() {}
        override fun pause() {}
        override fun flush() {}
        override fun stop() {}
        override fun release() {}
        override fun setVolume(volume: Float) {}
        override val playbackHeadPosition: Long get() = filled.toLong()
        override val underrunCount = 0
        override fun timestamp(into: FrameTime) = false

        override fun open(sampleRate: Int, kind: StreamKind, lowLatency: Boolean, bufferBytes: (Int) -> Int) = this
        override fun playClip(pcm: ShortArray, sampleRate: Int, kind: StreamKind) {}
    }

    /** Where each click starts: the first loud sample after a stretch of silence. */
    private fun onsets(s: ShortArray, upTo: Int): List<Int> {
        val out = mutableListOf<Int>()
        var quiet = Int.MAX_VALUE
        for (i in 0 until upTo) {
            if (abs(s[i].toInt()) > 200) {
                if (quiet > 2_000) out += i
                quiet = 0
            } else quiet++
        }
        return out
    }

    private fun run(seconds: Int, settings: MetronomeSettings, during: Pair<Int, (MetronomeEngine) -> Unit>? = null): Recording {
        val out = Recording(48_000 * seconds)
        val engine = MetronomeEngine(out)
        during?.let { (frame, action) -> out.at = frame to { action(engine) } }
        engine.start(settings)
        assertTrue("the metronome stopped writing", out.done.await(20, TimeUnit.SECONDS))
        engine.stop()
        return out
    }

    @Test
    fun `clicks at 120 bpm are exactly half a second apart`() {
        val out = run(10, MetronomeSettings(bpm = 120))
        val at = onsets(out.samples, out.filled)
        assertTrue("only ${at.size} clicks", at.size >= 19)
        at.zipWithNext { a, b -> b - a }.forEach { assertEquals(24_000, it) }
    }

    @Test
    fun `an odd tempo keeps its spacing to the sample over a long run`() {
        val out = run(30, MetronomeSettings(bpm = 97))
        val at = onsets(out.samples, out.filled)
        // 60 / 97 s is 29690.72 samples: each click is at its own rounded
        // sample, so the gaps vary by one and the total never drifts.
        at.zipWithNext { a, b -> b - a }.forEach { assertTrue("gap $it", it in 29_690..29_691) }
        val expected = (at.size - 1) * 48_000.0 * 60 / 97
        assertTrue("drifted ${at.last() - at.first() - expected} samples", abs(at.last() - at.first() - expected) <= 1.0)
    }

    @Test
    fun `a tempo change is heard from the next beat`() {
        val out = run(8, MetronomeSettings(bpm = 120), during = 48_000 * 3 to { it.setBpm(60) })
        val gaps = onsets(out.samples, out.filled).zipWithNext { a, b -> b - a }
        assertTrue(gaps.first() == 24_000)
        assertTrue(gaps.last() == 48_000)
        // At most one beat goes by at the old tempo once the change is asked for.
        assertTrue(gaps.dropWhile { it == 24_000 }.all { it == 48_000 })
    }
}
