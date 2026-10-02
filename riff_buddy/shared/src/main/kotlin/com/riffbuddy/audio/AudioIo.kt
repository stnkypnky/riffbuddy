package com.riffbuddy.audio

/**
 * Sound in and out, as the engines need it on any platform.
 *
 * Every engine here keeps its own timing: it writes a continuous stream and
 * places each sound at its own sample index, so nothing waits for a beat and
 * nothing drifts. What the platform supplies is only the pipe — a stream that
 * blocks until the hardware has room, a microphone that blocks until it has
 * samples, and the hardware's word on when a given frame actually reached the
 * speaker or left the microphone.
 *
 * All streams are 16-bit mono out and float mono in, which is everything the
 * app plays and records.
 */

/** What an output stream carries. Android uses it to route and mix the stream. */
enum class StreamKind {
    /** Clicks, beeps, reference tones: short sounds that must not be smoothed. */
    SONIFICATION,
    /** Songs, takes, phrases. */
    MUSIC,
}

/**
 * Frame [frame] of a stream was played (or captured) at [nanoTime] on the
 * monotonic clock. Mutable, so a render loop can reuse one instead of
 * allocating on every poll.
 */
class FrameTime {
    var frame = 0L
    var nanoTime = 0L
}

/** One output stream, opened by [AudioOutputs.open]. */
interface PcmOutput {
    val sampleRate: Int

    /** The buffer the platform granted, in frames. Prime this much before [play]. */
    val bufferFrames: Int

    /** Blocks until there is room; returns frames written, or a negative error. */
    fun write(pcm: ShortArray, offset: Int, frames: Int): Int

    fun play()
    fun pause()

    /** Throws away anything written but not yet played. */
    fun flush()
    fun stop()
    fun release()

    /** 0.0 to 1.0, applied by the platform's mixer. */
    fun setVolume(volume: Float)

    /** Frames the speaker has played since [play]. */
    val playbackHeadPosition: Long

    /** Times the hardware ran out of samples. */
    val underrunCount: Int

    /** Fills [into] with the latest frame the hardware reports having played; false if it hasn't said. */
    fun timestamp(into: FrameTime): Boolean
}

interface AudioOutputs {
    /**
     * A streaming output. [bufferBytes] turns the platform's smallest workable
     * buffer, in bytes, into the size this stream wants: each engine trades
     * latency against safety differently, and says how here.
     */
    fun open(
        sampleRate: Int,
        kind: StreamKind,
        lowLatency: Boolean = false,
        bufferBytes: (minimum: Int) -> Int,
    ): PcmOutput

    /** A short sound played once and released on its own: a cue, not a stream. */
    fun playClip(pcm: ShortArray, sampleRate: Int, kind: StreamKind)
}

/** One microphone or line input, opened by [AudioInputs.open]. Raw: no voice processing. */
interface PcmInput {
    fun start()

    /** Blocks until [frames] samples arrive; returns how many were read, or a negative error. */
    fun read(buffer: FloatArray, offset: Int, frames: Int): Int

    /** Fills [into] with the latest frame the hardware reports having captured; false if it hasn't said. */
    fun timestamp(into: FrameTime): Boolean

    /** Gives the input back. Must be called: a held microphone blocks every other app. */
    fun release()
}

interface AudioInputs {
    /** The inputs worth offering, the default first. */
    fun inputs(): List<RecordInput>

    /**
     * Opens [inputId] (null for the default) with speech processing off — no
     * automatic gain, noise suppression or echo cancellation, which would treat
     * a held guitar note as noise to fade. The caller holds any permission the
     * platform requires.
     */
    fun open(sampleRate: Int, bufferBytes: Int, inputId: Int? = null): PcmInput
}

/**
 * An input the recorder can use. [line] is true for an amp's own output, which
 * arrives at a very different level from a microphone in the room, so the two
 * keep separate gain settings.
 */
data class RecordInput(val id: Int?, val label: String, val line: Boolean)
