package com.riffbuddy.audio

import kotlin.math.ceil

/**
 * Where metronome clicks land inside one block of looper output (the looper's
 * metronome overlay).
 *
 * The beat grid lives in *song* time — a beat every 60/bpm seconds of the
 * recording — anchored at A, so beat one is the start of the loop on every
 * pass. The block covers `frames × speed` of song time; a beat that falls in
 * it is placed at `(beat − start) / speed` output frames in. At half speed the
 * clicks are half as fast, because they are following the music, not a clock.
 *
 * Placed to the sample, not rounded to the block: a block is ~23 ms, and a
 * click that is a block early or late is one a guitarist hears.
 */
object ClickGrid {

    data class Click(val offset: Int, val downbeat: Boolean)

    /**
     * [start] is the song position (frames) at the first sample of the block.
     * [loop] is start-inclusive, end-exclusive; null for straight playback,
     * where the grid is anchored at [anchor] instead.
     */
    fun clicks(
        start: Double,
        frames: Int,
        speed: Double,
        beatFrames: Double,
        beatsPerBar: Int,
        loop: IntRange?,
        anchor: Double = 0.0,
    ): List<Click> {
        val offsets = IntArray(MAX_PER_BLOCK)
        val downbeats = BooleanArray(MAX_PER_BLOCK)
        val n = clicksInto(start, frames, speed, beatFrames, beatsPerBar, loop, anchor, offsets, downbeats)
        return List(n) { Click(offsets[it], downbeats[it]) }
    }

    /**
     * The same, writing into caller-owned arrays and returning the count. This
     * is the one the render thread calls: it must not allocate (the project notes), and
     * a list per block is garbage forty times a second.
     */
    fun clicksInto(
        start: Double,
        frames: Int,
        speed: Double,
        beatFrames: Double,
        beatsPerBar: Int,
        loop: IntRange?,
        anchor: Double,
        offsets: IntArray,
        downbeats: BooleanArray,
    ): Int {
        if (beatFrames <= 0 || frames <= 0) return 0
        var count = 0
        var songPos = start
        var outputPos = 0.0
        var remaining = frames * speed
        val gridStart = loop?.first?.toDouble() ?: anchor
        val loopEnd = loop?.let { (it.last + 1).toDouble() }

        while (remaining > 0) {
            // The stretch of song until the loop wraps, or the whole block.
            val span = if (loopEnd != null) minOf(remaining, loopEnd - songPos) else remaining
            if (span <= 0) {
                songPos = gridStart
                continue
            }
            var k = ceil((songPos - gridStart) / beatFrames - 1e-9).toLong().coerceAtLeast(0)
            while (true) {
                val beatAt = gridStart + k * beatFrames
                if (beatAt >= songPos + span) break
                if (beatAt >= songPos) {
                    val offset = (outputPos + (beatAt - songPos) / speed).toInt()
                    if (offset in 0 until frames && count < offsets.size) {
                        offsets[count] = offset
                        downbeats[count] = k % beatsPerBar == 0L
                        count++
                    }
                }
                k++
            }
            outputPos += span / speed
            remaining -= span
            songPos += span
            if (loopEnd != null && songPos >= loopEnd) songPos = gridStart
        }
        return count
    }

    /** More than enough: 300 BPM at 150 % over a 1024-frame block is under one. */
    const val MAX_PER_BLOCK = 8
}
