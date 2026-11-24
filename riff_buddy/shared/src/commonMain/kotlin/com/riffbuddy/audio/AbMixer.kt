package com.riffbuddy.audio

/**
 * Plays one take, or two with instant A/B switching. Pure, so the
 * switching criterion is a unit test.
 *
 * **One position for both.** The mixer keeps a single musical position, in
 * frames from each take's own start point (its aligned start: where the
 * play-along source began, moved by the calibrated latency). Switching changes
 * only which take is read at that position — nothing seeks — so "switching A/B
 * keeps position within 50 ms" holds exactly: the position after a switch is
 * the position before it. A 10 ms cross-fade stops the switch clicking.
 *
 * Takes are assumed to share a sample rate, which the recorder guarantees
 * (48 kHz); the position is in frames of that rate.
 */
class AbMixer(private val takes: List<Pcm>, startFrames: List<Int>) {

    init {
        require(takes.size in 1..2) { "one take or two" }
        require(startFrames.size == takes.size)
        require(takes.all { it.sampleRate == takes[0].sampleRate }) { "takes must share a sample rate" }
    }

    private val starts = startFrames.mapIndexed { i, s -> s.coerceIn(0, takes[i].frames) }
    val sampleRate = takes[0].sampleRate

    /** Frames from the aligned start. */
    var position: Long = 0
        private set

    /** Which take is heard: 0 is A, 1 is B. */
    var selected: Int = 0
        private set

    /** The longer of the takes, measured from its aligned start. */
    val length: Long = takes.indices.maxOf { (takes[it].frames - starts[it]).toLong() }

    val finished: Boolean get() = position >= length

    private val fadeFrames = sampleRate / 100
    private var fadeLeft = 0
    private var fadingFrom = 0

    fun select(index: Int) {
        val i = index.coerceIn(0, takes.size - 1)
        if (i == selected) return
        fadingFrom = selected
        selected = i
        fadeLeft = fadeFrames
    }

    fun seek(frame: Long) {
        position = frame.coerceIn(0, length)
        fadeLeft = 0
    }

    private fun sampleOf(take: Int, pos: Long): Float {
        val i = starts[take] + pos
        val pcm = takes[take]
        return if (i < 0 || i >= pcm.frames) 0f else pcm.samples[i.toInt()].toFloat()
    }

    /** Fills [out]; returns how many frames were before the end. */
    fun render(out: ShortArray, frames: Int = out.size): Int {
        var real = 0
        for (k in 0 until frames) {
            if (position >= length) { out[k] = 0; continue }
            var v = sampleOf(selected, position)
            if (fadeLeft > 0) {
                val w = fadeLeft.toFloat() / fadeFrames
                v = v * (1 - w) + sampleOf(fadingFrom, position) * w
                fadeLeft--
            }
            out[k] = v.toInt().coerceIn(-32768, 32767).toShort()
            position++
            real++
        }
        return real
    }
}
