package com.riffbuddy.domain

import kotlin.time.Clock
import kotlin.time.Instant

/**
 * How far along a part of a song is. Five stages rather than a
 * checkbox or a percentage: "clean but slow" and "up to speed" are the two
 * places a guitarist actually stalls, and a stage says which one you're in.
 */
enum class PartStage(val label: String) {
    NOT_STARTED("Not started"),
    LEARNING("Learning"),
    CLEAN_SLOW("Clean but slow"),
    UP_TO_SPEED("Up to speed"),
    MASTERED("Mastered");

    /** One tap moves a part on; Mastered stays Mastered. */
    fun next(): PartStage = entries.getOrElse(ordinal + 1) { MASTERED }

    /** 0 for not started, 1 for mastered, evenly between. */
    val fraction: Float get() = ordinal / (entries.size - 1).toFloat()

    companion object {
        fun fromName(raw: String?): PartStage =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: NOT_STARTED
    }
}

/** A part of a song: the intro, the solo, the chorus riff. */
data class SongPart(
    val id: Long = 0,
    val songId: Long,
    val name: String,
    val position: Int = 0,
    val stage: PartStage = PartStage.NOT_STARTED,
    val stageChangedAt: Instant? = null,
    val masteredAt: Instant? = null,
    val createdAt: Instant = EPOCH,
)

object Mastery {

    /**
     * Offered when adding a part, A–Z in a dropdown; anything else can be typed.
     * Numbers aren't listed ("Verse 2"): picking a name the song already has
     * numbers it, see [uniqueName].
     */
    val COMMON_PARTS = listOf(
        "Intro", "Intro riff", "Main riff", "Verse", "Verse riff", "Pre-chorus", "Chorus", "Chorus riff",
        "Post-chorus", "Hook", "Refrain", "Bridge", "Middle eight", "Interlude", "Break", "Breakdown",
        "Build-up", "Instrumental", "Riff", "Lead", "Lead fill", "Harmony lead", "Rhythm part",
        "Solo", "Outro solo", "Dual solo", "Fill", "Turnaround", "Transition", "Clean section",
        "Heavy section", "Palm-muted section", "Arpeggios", "Acoustic part", "Ending", "Outro", "Coda", "Tag",
    )

    /**
     * [name], or "Verse 2", "Verse 3"… when the song already has it, so a
     * second verse or solo can come straight from the list. Case doesn't count
     * as different: "verse" and "Verse" are the same part.
     */
    fun uniqueName(name: String, existing: Collection<String>): String {
        val taken = existing.map { it.trim().lowercase() }.toSet()
        if (name.trim().lowercase() !in taken) return name
        return generateSequence(2) { it + 1 }.map { "$name $it" }.first { it.lowercase() !in taken }
    }

    /** How far through the song is, from its parts. Null when it has none. */
    fun progress(parts: List<SongPart>): Float? =
        if (parts.isEmpty()) null else parts.map { it.stage.fraction }.average().toFloat()

    fun allMastered(parts: List<SongPart>): Boolean =
        parts.isNotEmpty() && parts.all { it.stage == PartStage.MASTERED }
}

/**
 * A song's story, told when it's mastered: how long it's been since it was
 * added, how much time went into it, over how many sessions, and how far its
 * tempo came.
 */
data class Journey(
    val days: Long,
    val seconds: Long,
    val sessions: Int,
    val firstBpm: Int?,
    val bestBpm: Int?,
) {
    companion object {
        fun of(song: Song, sessions: List<Session>, now: Instant = Clock.System.now()): Journey {
            val mine = sessions.filter { s -> s.items.any { it.songId == song.id } }
            val items = mine.sortedBy { it.startedAt }.flatMap { s -> s.items.filter { it.songId == song.id } }
            val bpms = items.mapNotNull { it.bpmEnd ?: it.bpmStart }
            val start = song.createdAt.takeIf { it != EPOCH } ?: mine.minOfOrNull { it.startedAt } ?: now
            return Journey(
                days = (now - start).inWholeDays.coerceAtLeast(0),
                seconds = items.sumOf { it.seconds.toLong() },
                sessions = mine.size,
                firstBpm = bpms.firstOrNull(),
                bestBpm = bpms.maxOrNull(),
            )
        }
    }
}
