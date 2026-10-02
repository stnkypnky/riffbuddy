package com.riffbuddy.ai.prompts

import com.riffbuddy.domain.Song

/**
 * The three song assists, versioned like every other prompt.
 *
 * A shared rule runs through all of them: the coach has not heard the song, and
 * will write fluently either way. So each prompt asks it to say when it is
 * guessing — which is only useful if the app then shows that admission rather
 * than burying it.
 */
object AssistPrompts {

    private const val HONESTY =
        "You may not know this song. If you are unsure, say so plainly in your answer " +
            "rather than inventing specifics. Never reproduce lyrics, tab or notation."

    /**
     * Difficulty feedback is fed back in, so estimates calibrate.
     *
     * Without this the coach would say "7 out of 10" about a song the player
     * found easy, forever, and the number would mean nothing to them.
     */
    private fun calibration(previousRating: Int?, feedback: String?): String = when {
        previousRating == null && feedback == null -> ""
        else -> buildString {
            appendLine()
            append("Calibration: ")
            if (previousRating != null) append("you previously rated this $previousRating/10. ")
            when (feedback) {
                "too_easy" -> append("The player found that too generous — it was easier than you said.")
                "too_hard" -> append("The player found that too harsh — it was harder than you said.")
                else -> {}
            }
        }
    }

    fun similarSystem(level: String, styles: String): String =
        """
        You suggest songs to learn next, for a guitarist.
        $HONESTY

        The player is $level and likes: $styles.

        Give five to eight suggestions. For each: why it follows from the song they
        asked about, its usual tuning, and whether it is easier, similar or harder.
        Never suggest the song they asked about. Prefer songs that teach something
        the original does not.
        """.trimIndent()

    fun difficultySystem(
        level: String,
        styles: String,
        songbookSummary: String,
        previousRating: Int?,
        feedback: String?,
    ): String =
        """
        You judge how hard a song is for one specific guitarist.
        $HONESTY

        The player is $level and likes: $styles.
        What they already play: $songbookSummary
        ${calibration(previousRating, feedback)}

        Rate it 1-10 *for this player*, not in the abstract, and say how it compares
        to what they already play. Give the factors that drive the rating, anything
        they should be able to do first, and three steps to approach it — three, not
        ten, and each one something they could do this week.
        """.trimIndent()

    fun howToSystem(level: String): String =
        """
        You explain how a song is built, to a $level guitarist who is about to learn it.
        $HONESTY

        Walk through its sections in order and say what happens in each and what
        catches people out. Name the techniques it needs. List the mistakes people
        actually make with it.

        Describe the music in words. Do not write out tab, notation or lyrics — not
        even a fragment, and not as an example.
        """.trimIndent()

    fun user(song: Song): String = buildString {
        append("\"${song.title}\"")
        if (song.artist.isNotBlank()) append(" by ${song.artist}")
        song.key?.takeIf { it.isNotBlank() }?.let { append(", key of $it") }
        song.targetBpm?.let { append(", around $it BPM") }
        if (song.capo > 0) append(", capo ${song.capo}")
        append(".")
    }
}
