package com.riffbuddy.domain

import kotlin.time.Instant

/** What a take was recorded against. */
enum class TakeSource(val label: String) { NONE("On its own"), METRONOME("With the metronome"), LOOPER("With the looper") }

/** A take: one recording from the recorder. */
data class Recording(
    val id: Long = 0,
    val songId: Long? = null,
    val sessionId: Long? = null,
    val name: String,
    val storedName: String,
    val durationMs: Long = 0,
    val sizeBytes: Long = 0,
    val sampleRate: Int = 48_000,
    /** The calibration in force when it was recorded. */
    val latencyOffsetMs: Int = 0,
    /** Where in the take a play-along source began; null when there was none. */
    val alignMs: Long? = null,
    val source: TakeSource = TakeSource.NONE,
    val starred: Boolean = false,
    val rating: Int? = null,
    val notes: String = "",
    val createdAt: Instant = EPOCH,
) {
    /**
     * Where playback of this take should start to line up with its source, and
     * with any other take of the same thing: the source's start,
     * moved by the calibrated latency. Zero for a take made on its own.
     */
    val startMs: Long get() = alignMs?.let { (it + latencyOffsetMs).coerceAtLeast(0) } ?: 0L
}
