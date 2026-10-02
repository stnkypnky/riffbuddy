package com.riffbuddy.domain

/** One track's mixer settings. Volume is 0.0–1.0, 1.0 being the file's own level. */
data class TrackMix(val muted: Boolean = false, val solo: Boolean = false, val volume: Float = 1f) {
    val isDefault: Boolean get() = this == TrackMix()
}

/**
 * How a tab file's player was left: which tracks are muted, soloed or turned
 * down, which one is drawn, zoom, layout, count-in and click.
 *
 * Remembered per file and put back without asking. A file with a sound-effect
 * track that gets turned off every time it is opened should not need turning
 * off again: a file whose settings were changed opens the way it was left.
 *
 * Stored as one short text column rather than a table: it is only ever read
 * and written whole, alongside the row it belongs to, and it rides along in
 * a backup with no extra work. Tracks are keyed by their position in the
 * file, which can't shift, since a file's bytes never change once imported.
 */
data class TabPrefs(
    /** Null: let the player pick (a guitar track, if there is one). */
    val shownTrack: Int? = null,
    val zoom: Float = 1f,
    val horizontal: Boolean = false,
    val countIn: Boolean = false,
    val metronome: Boolean = false,
    val tracks: Map<Int, TrackMix> = emptyMap(),
) {
    fun mix(track: Int): TrackMix = tracks[track] ?: TrackMix()

    /**
     * `show=2;zoom=1.2;hor=1;count=0;click=0;t0=m,-,0.80` — only what
     * differs from the defaults is written, so an untouched file stores
     * nothing at all.
     */
    fun encode(): String? = buildList {
        shownTrack?.let { add("show=$it") }
        if (zoom != 1f) add("zoom=$zoom")
        if (horizontal) add("hor=1")
        if (countIn) add("count=1")
        if (metronome) add("click=1")
        tracks.toSortedMap().forEach { (i, m) ->
            if (!m.isDefault) add("t$i=${if (m.muted) "m" else "-"},${if (m.solo) "s" else "-"},${m.volume}")
        }
    }.joinToString(";").ifEmpty { null }

    companion object {
        /**
         * Anything it can't read is skipped, not an error: a bad field costs
         * that one setting, never the file.
         */
        fun decode(stored: String?): TabPrefs {
            if (stored.isNullOrBlank()) return TabPrefs()
            var prefs = TabPrefs()
            val tracks = mutableMapOf<Int, TrackMix>()
            stored.split(";").forEach { field ->
                val key = field.substringBefore("=", "").trim()
                val value = field.substringAfter("=", "").trim()
                when {
                    key == "show" -> value.toIntOrNull()?.takeIf { it >= 0 }?.let { prefs = prefs.copy(shownTrack = it) }
                    key == "zoom" -> value.toFloatOrNull()?.takeIf { it.isFinite() }?.let { prefs = prefs.copy(zoom = it) }
                    key == "hor" -> prefs = prefs.copy(horizontal = value == "1")
                    key == "count" -> prefs = prefs.copy(countIn = value == "1")
                    key == "click" -> prefs = prefs.copy(metronome = value == "1")
                    key.startsWith("t") -> {
                        val index = key.drop(1).toIntOrNull() ?: return@forEach
                        val parts = value.split(",")
                        if (index < 0 || parts.size != 3) return@forEach
                        val volume = parts[2].toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: return@forEach
                        tracks[index] = TrackMix(parts[0] == "m", parts[1] == "s", volume)
                    }
                }
            }
            return prefs.copy(tracks = tracks)
        }
    }
}
