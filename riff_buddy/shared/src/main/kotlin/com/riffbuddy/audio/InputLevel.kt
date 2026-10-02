package com.riffbuddy.audio

import kotlin.math.abs
import kotlin.math.log10

/**
 * The recorder's level meter and clipping warning, as pure arithmetic.
 *
 * Peak rather than average, because clipping is a peak event: a take can sit
 * comfortably on an average meter while every pick attack flattens against
 * full scale. "Clipped" is within a hair of full scale rather than exactly at
 * it — converters rarely report the last code, and a warning that only fires on
 * 32767 exactly is one that almost never fires.
 */
object InputLevel {

    const val FLOOR_DB = -60f
    const val CLIP_AT = 32_600

    class Reading(val peakDb: Float, val clipped: Boolean)

    fun read(samples: ShortArray, count: Int): Reading {
        var peak = 0
        for (i in 0 until count) peak = maxOf(peak, abs(samples[i].toInt()))
        return Reading(toDb(peak), peak >= CLIP_AT)
    }

    fun toDb(peak: Int): Float {
        if (peak <= 0) return FLOOR_DB
        return (20 * log10(peak / 32768.0)).toFloat().coerceIn(FLOOR_DB, 0f)
    }

    /** 0..1 for drawing: the floor at 0, full scale at 1. */
    fun meter(db: Float): Float = ((db - FLOOR_DB) / -FLOOR_DB).coerceIn(0f, 1f)
}
