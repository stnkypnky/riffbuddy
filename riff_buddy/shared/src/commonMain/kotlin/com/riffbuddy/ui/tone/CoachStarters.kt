package com.riffbuddy.ui.tone

import com.riffbuddy.data.Rig
import com.riffbuddy.domain.PlayerProfile
import com.riffbuddy.domain.Session
import com.riffbuddy.domain.Song
import com.riffbuddy.domain.SongStatus
import com.riffbuddy.domain.format
import com.riffbuddy.domain.local
import com.riffbuddy.domain.localDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlin.time.Instant

/** What a suggested first question is about; each has its own icon on the card. */
enum class StarterKind(val label: String) {
    TECHNIQUE("Technique"),
    TONE("Tone"),
    THEORY("Theory"),
    PRACTICE("Practice plan"),
}

data class Starter(val kind: StarterKind, val question: String)

/**
 * The words on the coach's welcome screen, worked out from the player's own
 * data. Pure, so the wording can be tested without drawing anything.
 *
 * Nothing here names gear or songs of its own: every title and model comes
 * from what the player has entered, and with nothing entered the coach falls
 * back to questions that suit anyone.
 */
object CoachHome {

    /** A greeting for the hour of the day, 0 to 23. */
    fun greeting(hour: Int): String = when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..21 -> "Good evening"
        else -> "Up late? Let's play"
    }

    /** Whole calendar days from [then] to [now] where the player is: 0 is today, 1 yesterday. */
    fun daysAgo(then: Instant, now: Instant, zone: TimeZone): Int =
        then.localDate(zone).daysUntil(now.localDate(zone))

    /**
     * When a chat was last used, the way a person says it: "Just now",
     * "12 min ago", "Today, 3:05 PM", "Yesterday", "4 days ago", "Mar 4".
     */
    fun whenAgo(then: Instant, now: Instant, zone: TimeZone): String {
        val seconds = (now - then).inWholeSeconds
        val days = daysAgo(then, now, zone)
        return when {
            seconds < 60 -> "Just now"
            seconds < 3600 && days == 0 -> "${seconds / 60} min ago"
            days <= 0 -> "Today, ${then.local(zone).format("h:mm a")}"
            days == 1 -> "Yesterday"
            days < 7 -> "$days days ago"
            then.local(zone).year == now.local(zone).year -> then.local(zone).format("MMM d")
            else -> then.local(zone).format("MMM d, yyyy")
        }
    }

    /**
     * One line of what the coach will take into account, so the player can
     * see it is their coach before asking anything: "I know your Strat into
     * your Blues Junior, the 3 songs you're learning, and that your last
     * session was yesterday." Null when there is nothing to say yet.
     */
    fun knows(rig: Rig?, songs: List<Song>, sessions: List<Session>, now: Instant, zone: TimeZone): String? {
        val parts = buildList {
            val guitar = rig?.guitar?.name
            val amp = rig?.amp?.name
            when {
                guitar != null && amp != null -> add("your $guitar into the $amp")
                amp != null -> add("your $amp")
                guitar != null -> add("your $guitar")
            }
            val learning = songs.filter { it.status == SongStatus.LEARNING }
            when {
                learning.size == 1 -> add("that you're learning ${learning.single().title}")
                learning.size > 1 -> add("the ${learning.size} songs you're learning")
                songs.isNotEmpty() -> add(if (songs.size == 1) "the song in your songbook" else "the ${songs.size} songs in your songbook")
            }
            lastSession(sessions)?.let { last ->
                add(
                    when (val d = daysAgo(last, now, zone)) {
                        in Int.MIN_VALUE..0 -> "that you've already practiced today"
                        1 -> "that your last session was yesterday"
                        else -> "that your last session was $d days ago"
                    }
                )
            }
        }
        if (parts.isEmpty()) return null
        val list = when (parts.size) {
            1 -> parts[0]
            2 -> "${parts[0]} and ${parts[1]}"
            else -> parts.dropLast(1).joinToString(", ") + ", and " + parts.last()
        }
        return "I know $list."
    }

    /**
     * Suggested first questions, one of each kind before a second of any, so a
     * short list still covers technique, tone, theory and a plan.
     *
     * Each kind lists its candidates most personal first: a song with a tempo
     * gap beats a song, which beats the player's goals, which beat a question
     * anyone might ask.
     */
    fun starters(
        rig: Rig?,
        songs: List<Song>,
        sessions: List<Session> = emptyList(),
        player: PlayerProfile = PlayerProfile(),
        now: Instant,
        zone: TimeZone,
        limit: Int = 6,
    ): List<Starter> {
        val amp = rig?.amp?.name
        val guitar = rig?.guitar?.name
        val focus = focusSong(songs, sessions)
        // A song with a tempo still to reach is the most concrete thing to work on.
        val gap = songs.filter { it.status == SongStatus.LEARNING || it.status == SongStatus.POLISHING }
            .firstOrNull { s -> val t = s.targetBpm; val c = s.currentBpm; t != null && c != null && c < t }
        val beginner = player.level == PlayerProfile.Level.BEGINNER
        val goals = player.goals.map { it.lowercase() }
        val styles = player.styles.map { it.lowercase() }
        val last = lastSession(sessions)
        val idleDays = last?.let { daysAgo(it, now, zone) }

        val technique = buildList {
            if (gap != null) add("I'm playing ${gap.title} at ${gap.currentBpm} BPM and want ${gap.targetBpm}. How do I close the gap without getting sloppy?")
            if (focus != null && focus != gap) add("What's likely to be the hardest part of ${focus.title}, and how do I drill it?")
            if ("play faster" in goals) add("How do I build speed without tensing up?")
            if (beginner) add("How do I stop strings buzzing when I fret a chord?")
            add("What should I practice to get faster at chord changes?")
        }
        val tone = buildList {
            if (focus != null && amp != null) add("How do I get the ${focus.title} tone on my $amp?")
            if (amp != null && styles.isNotEmpty()) add("Set up a classic ${styles.first()} tone on my $amp.")
            if (amp != null) add("What's a great clean sound on my $amp, and how do I add some grit?")
            if (guitar != null) add("How do I get the most out of the pickups on my $guitar?")
            add("What should I listen for when I dial in a tone?")
        }
        val theory = buildList {
            val keyed = songs.firstOrNull { it == focus && !it.key.isNullOrBlank() }
                ?: songs.firstOrNull { it.status == SongStatus.LEARNING && !it.key.isNullOrBlank() }
            if (keyed != null) add("What scale fits ${keyed.title} in ${keyed.key}, in fret positions?")
            if ("improvise" in goals || "blues" in styles) add("How do I start improvising over a blues?")
            if ("understand theory" in goals) add("Why do the chords in a key fit together? Explain it on the fretboard.")
            if (beginner) add("What's the minor pentatonic, shown on the fretboard?")
            add("Which scale shape should I learn next, and why?")
        }
        val practice = buildList {
            if (idleDays != null && idleDays >= 3) add("It's been $idleDays days since I played. What's a good 20-minute session to get back in?")
            if (focus != null) add("Plan my next 30 minutes around ${focus.title}.")
            if (last == null) add("Plan a 20-minute daily routine for a ${player.level.label.lowercase()} player.")
            add("Give me a 30-minute practice plan for this week.")
        }

        val byKind = listOf(
            StarterKind.TECHNIQUE to technique,
            StarterKind.TONE to tone,
            StarterKind.THEORY to theory,
            StarterKind.PRACTICE to practice,
        )
        return buildList {
            for (round in 0 until 2) {
                byKind.forEach { (kind, questions) ->
                    questions.getOrNull(round)?.let { add(Starter(kind, it)) }
                }
            }
        }.take(limit)
    }

    /** The song to build suggestions around: the learning song played most recently, else the first one. */
    fun focusSong(songs: List<Song>, sessions: List<Session>): Song? {
        val learning = songs.filter { it.status == SongStatus.LEARNING }
        if (learning.isEmpty()) return null
        val played = sessions.sortedByDescending { it.startedAt }
            .asSequence()
            .flatMap { s -> s.items.asSequence().mapNotNull { it.songId } }
            .firstOrNull { id -> learning.any { it.id == id } }
        return learning.firstOrNull { it.id == played } ?: learning.first()
    }

    private fun lastSession(sessions: List<Session>): Instant? = sessions.maxOfOrNull { it.startedAt }
}
