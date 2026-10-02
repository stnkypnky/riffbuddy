package com.riffbuddy.audio

/**
 * When an output stream's first sample actually left the speaker, on the
 * monotonic clock.
 *
 * The output's timestamp reports "frame N was presented at time T". Frame 0
 * of the stream is then T − N / rate. The recorder reports its input on the same
 * clock, so a play-along take can be told where in it the backing began.
 *
 * Polled from a render thread every so often: the first timestamps after
 * `play()` are often missing or rough, and a later one is better. Allocates
 * nothing, so it is safe in a render loop.
 */
class OutputClock {

    private val ts = FrameTime()
    private var polls = 0

    /** Monotonic nanoseconds at which the stream's frame 0 was played; null until known. */
    @Volatile var streamStartNanos: Long? = null
        private set

    /** Whether the time came from the hardware rather than the [played] fallback. */
    @Volatile var hardwareClock = false
        private set

    fun reset() {
        streamStartNanos = null
        hardwareClock = false
        polls = 0
    }

    /**
     * Fallback: `play()` was just called, so frame 0 is about to be heard.
     * Early by the output buffer — fixed on a given device, and measured away by
     * calibration. Replaced as soon as the hardware reports a real timestamp.
     */
    fun played() {
        if (streamStartNanos == null) streamStartNanos = System.nanoTime()
    }

    /** Call after each write once playing. Samples every [every] calls. */
    fun poll(output: PcmOutput, sampleRate: Int, every: Int = 20) {
        if (polls++ % every != 0) return
        if (!output.timestamp(ts)) return
        streamStartNanos = ts.nanoTime - ts.frame * 1_000_000_000L / sampleRate
        hardwareClock = true
    }
}
