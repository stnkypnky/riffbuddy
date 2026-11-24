package com.riffbuddy.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A whole decoded song, mono 16-bit, in memory. */
class Pcm(val samples: ShortArray, val sampleRate: Int) {
    val frames: Int get() = samples.size
    fun msToFrame(ms: Long): Int = (ms * sampleRate / 1000).toInt().coerceIn(0, frames)
    fun frameToMs(frame: Double): Long = (frame * 1000 / sampleRate).toLong()
}

/**
 * Reads a song at any speed from 25 % to 150 % with the pitch unchanged, and
 * loops an A–B region with no gap. Pure Kotlin: the engine feeds it to the
 * audio output, and the tests feed it to the tuner's own PitchDetector.
 *
 * **Speed without pitch: WSOLA.** The song is cut into overlapping slices about
 * 40 ms long, each played at its original rate — that is what keeps the pitch.
 * Speed is only how fast the cutting point moves through the song: at 50 % it
 * advances half a slice-step per slice, so every moment is heard about twice.
 * Laid down blindly, neighboring slices would meet out of phase and cancel
 * into a warble, so each cut is nudged up to 12 ms either way to wherever the
 * waveform best continues the slice before it. That search is the "waveform
 * similarity" in Waveform-Similarity Overlap-Add.
 *
 * **The loop has no seek.** Every read goes through [sample], which folds any
 * position past B back to A. There is nothing to wait for at the join, so there
 * is no gap to measure — the loop is as exact as the sample index. The last
 * few milliseconds before B are cross-faded into the audio just before A, so
 * the join is a blend rather than a click; the loop still lands on A exactly.
 *
 * At exactly 100 % the stretcher is bypassed and samples are copied as they
 * are: at normal speed, nothing should touch the sound.
 */
class LoopRenderer(val pcm: Pcm) {

    /** Source position, in frames, of the next sample to be heard. */
    var position: Double = 0.0
        private set

    var speed: Double = 1.0
        set(value) { field = value.coerceIn(MIN_SPEED, MAX_SPEED) }

    /** Loop start and end in frames, end exclusive; null plays straight through. */
    var loop: IntRange? = null
        private set

    /** Times playback has wrapped from B back to A. The loop trainer counts these. */
    var loopsCompleted: Int = 0
        private set

    /** True once the end of the song has been played with no loop set. */
    val finished: Boolean get() = loop == null && position >= pcm.frames

    // ---- WSOLA state -------------------------------------------------------

    private val sr = pcm.sampleRate
    /** Slice length: 40 ms, even. */
    private val n = ((sr * 0.040).roundToInt() / 2) * 2
    /** Output advances half a slice per step; with a Hann window that sums flat. */
    private val hop = n / 2
    /** How far a cut may move to find a good join. */
    private val tolerance = (sr * 0.012).roundToInt()
    private val window = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / n)).toFloat() }

    /** Overlap-add accumulator: the finished first half is emitted each step. */
    private val ola = FloatArray(n)
    private val ready = FloatArray(hop)
    private var readyAt = hop          // nothing ready yet
    private var lastCut = Double.NaN   // where the previous slice was taken from
    private val crossfade = (sr * 0.006).roundToInt()

    fun seek(frame: Double) {
        val l = loop
        position = if (l != null && (frame < l.first || frame >= l.last + 1)) l.first.toDouble()
        else frame.coerceIn(0.0, pcm.frames.toDouble())
        resetStretch()
    }

    /**
     * Sets or clears the loop. A loop shorter than [MIN_LOOP_MS] is refused:
     * below that, a slice of the stretcher is longer than the loop itself.
     */
    fun setLoop(startFrame: Int?, endFrame: Int?): Boolean {
        if (startFrame == null || endFrame == null) {
            loop = null
            return true
        }
        val a = startFrame.coerceIn(0, pcm.frames)
        val b = endFrame.coerceIn(0, pcm.frames)
        if (b - a < sr * MIN_LOOP_MS / 1000) return false
        loop = a until b
        loopsCompleted = 0
        if (position < a || position >= b) seek(a.toDouble())
        return true
    }

    /**
     * Fills [out] with [frames] samples. Returns how many were real audio;
     * the rest (only possible at the end of a song with no loop) are silence.
     */
    fun render(out: ShortArray, frames: Int = out.size): Int {
        if (speed == 1.0) return copy(out, frames)
        var written = 0
        while (written < frames) {
            if (finished) break
            if (readyAt >= hop) step()
            val take = minOf(hop - readyAt, frames - written)
            for (i in 0 until take) out[written + i] = clip(ready[readyAt + i])
            readyAt += take
            written += take
        }
        for (i in written until frames) out[i] = 0
        return written
    }

    // ---- the two paths -----------------------------------------------------

    private fun copy(out: ShortArray, frames: Int): Int {
        var written = 0
        while (written < frames && !finished) {
            out[written++] = clip(sample(position.toInt()))
            advance(1.0)
        }
        for (i in written until frames) out[i] = 0
        // A later speed change starts the stretcher from here, not from wherever
        // it last left off.
        resetStretch()
        return written
    }

    /** One WSOLA step: choose a cut, overlap-add it, release half a slice. */
    private fun step() {
        val nominal = position
        val cut = if (lastCut.isNaN()) nominal else bestCut(nominal)
        for (i in 0 until n) ola[i] += sample(foldIndex(cut + i)) * window[i]
        // The first half is complete: nothing later overlaps it.
        ola.copyInto(ready, 0, 0, hop)
        ola.copyInto(ola, 0, hop, n)
        ola.fill(0f, n - hop, n)
        readyAt = 0
        lastCut = cut
        advance(hop * speed)
    }

    /**
     * The cut near [nominal] whose opening best continues the previous slice.
     *
     * "Continues" is concrete: the previous slice was taken from lastCut, so the
     * audio that naturally follows its overlap half starts at lastCut + hop.
     * Each candidate's first half is correlated against that.
     *
     * Both regions are copied into flat arrays first, so the search is plain
     * multiply-adds with no loop folding in the inner loop — and it runs
     * coarsely every 4 samples, then finely around the winner. A slow tablet
     * is the performance floor, and this runs fifty times a second of sound.
     */
    private fun bestCut(nominal: Double): Double {
        val base = floor(nominal).toInt() - tolerance
        val target = floor(lastCut).toInt() + hop
        for (i in 0 until hop) targetBuf[i] = sample(foldIndex(target + i.toDouble()))
        for (i in searchBuf.indices) searchBuf[i] = sample(foldIndex(base + i.toDouble()))

        var best = 0
        var bestScore = Float.NEGATIVE_INFINITY
        var d = 0
        while (d <= 2 * tolerance) {
            val s = score(d, 4)
            if (s > bestScore) { bestScore = s; best = d }
            d += 4
        }
        val coarse = best
        for (f in (coarse - 3)..(coarse + 3)) {
            if (f < 0 || f > 2 * tolerance || f == coarse) continue
            val s = score(f, 1)
            if (s > bestScore) { bestScore = s; best = f }
        }
        return (base + best).toDouble() + (nominal - floor(nominal))
    }

    /**
     * Correlation of the target with the candidate at [offset], normalized by
     * the candidate's energy only — the target's is the same for every
     * candidate, so it cannot change which one wins.
     */
    private fun score(offset: Int, stride: Int): Float {
        var dot = 0f
        var energy = 0f
        var i = 0
        while (i < hop) {
            val y = searchBuf[offset + i]
            dot += targetBuf[i] * y
            energy += y * y
            i += stride
        }
        return if (energy == 0f) 0f else dot / sqrt(energy)
    }

    private val targetBuf = FloatArray(hop)
    private val searchBuf = FloatArray(2 * tolerance + hop + 1)

    // ---- reading through the loop -----------------------------------------

    private fun advance(by: Double) {
        position += by
        val l = loop ?: return
        val length = (l.last + 1 - l.first).toDouble()
        while (position >= l.last + 1) {
            position -= length
            loopsCompleted++
        }
    }

    /**
     * Any position, folded into the loop when there is one. Positions just
     * before A are left alone — a cut searched a few ms early reads the real
     * audio there, which is what makes the first slice after a wrap join well.
     */
    private fun foldIndex(p: Double): Int {
        val i = floor(p).toInt()
        val l = loop ?: return i
        val end = l.last + 1
        if (i < end) return i
        return l.first + (i - l.first) % (end - l.first)
    }

    /**
     * The sample at a folded index, with the loop's join blended: over the
     * last few milliseconds before B, the audio fades into the audio just
     * before A, so that arriving at A continues the same waveform.
     */
    private fun sample(i: Int): Float {
        if (i < 0 || i >= pcm.frames) return 0f
        val here = pcm.samples[i].toFloat()
        val l = loop ?: return here
        val end = l.last + 1
        val intoFade = i - (end - crossfade)
        if (intoFade < 0 || i >= end) return here
        val partner = l.first - (end - i)
        if (partner < 0) return here
        val w = (intoFade + 1).toFloat() / crossfade
        return here * (1 - w) + pcm.samples[partner] * w
    }

    private fun resetStretch() {
        ola.fill(0f)
        readyAt = hop
        lastCut = Double.NaN
    }

    private fun clip(v: Float): Short = v.roundToInt().coerceIn(-32768, 32767).toShort()

    companion object {
        const val MIN_SPEED = 0.25
        const val MAX_SPEED = 1.5
        /** Shorter than this and a stretcher slice would not fit inside the loop. */
        const val MIN_LOOP_MS = 250
    }
}
