package com.riffbuddy.domain

import java.time.Instant

/** Where a song has got to. */
enum class SongStatus(val label: String) {
    WISHLIST("Wishlist"),
    LEARNING("Learning"),
    POLISHING("Polishing"),
    PERFORMANCE_READY("Performance-ready"),
    /** Was "Retired" once: a song you've learned is mastered, not put out to pasture. */
    MASTERED("Mastered");

    companion object {
        fun fromName(raw: String?): SongStatus =
            // Older backups still say RETIRED.
            if (raw.equals("retired", ignoreCase = true)) MASTERED
            else entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: WISHLIST
    }
}

/** How the songbook list is ordered. */
enum class SongSort(val label: String) {
    RECENT("Recently practiced"),
    TITLE("Title"),
    DIFFICULTY("Difficulty"),
    ADDED("Recently added"),
}

data class Song(
    val id: Long = 0,
    val title: String,
    val artist: String = "",
    val status: SongStatus = SongStatus.WISHLIST,
    val tuningId: Long? = null,
    val capo: Int = 0,
    val key: String? = null,
    val timeSignature: String? = null,
    val targetBpm: Int? = null,
    val currentBpm: Int? = null,
    val selfDifficulty: Int? = null,
    val tags: String = "",
    val notes: String = "",
    val songsterrUrl: String? = null,
    /** When the song was marked Mastered; null otherwise. */
    val masteredAt: Instant? = null,
    val createdAt: Instant = Instant.EPOCH,
    val updatedAt: Instant = Instant.EPOCH,
) {
    val tagList: List<String>
        get() = tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    /** "Artist — Title", or just the title when the artist is unknown. */
    val displayName: String
        get() = if (artist.isBlank()) title else "$artist — $title"
}

/**
 * Searching, filtering and sorting the songbook.
 *
 * Done in Kotlin over the whole list rather than in SQL. For 500 songs — a
 * large songbook — that is well under a millisecond, and it keeps matching
 * behavior in one place where it can be tested without a database. If the
 * songbook ever reaches a size where this matters, the fix is an index and a
 * query, not cleverness here.
 */
object Songbook {

    /**
     * Case- and punctuation-insensitive form, for comparing two songs.
     *
     * "Nothing Else Matters" and "nothing else matters" are the same song, and
     * so is "Dont Stop Believin" against "Don't Stop Believin'". Getting this
     * wrong means the duplicate warning never fires on the cases that actually
     * happen, which are typos and apostrophes.
     */
    fun normalize(value: String): String =
        value.lowercase().filter { it.isLetterOrDigit() || it.isWhitespace() }
            .split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    /** Adding a song that already exists should warn, not silently duplicate. */
    fun findDuplicate(existing: List<Song>, title: String, artist: String, ignoreId: Long = 0): Song? {
        val t = normalize(title)
        val a = normalize(artist)
        if (t.isEmpty()) return null
        return existing.firstOrNull {
            it.id != ignoreId && normalize(it.title) == t && normalize(it.artist) == a
        }
    }

    /** Matches title, artist or any tag. */
    fun matches(song: Song, query: String): Boolean {
        val q = normalize(query)
        if (q.isEmpty()) return true
        return normalize(song.title).contains(q) ||
            normalize(song.artist).contains(q) ||
            song.tagList.any { normalize(it).contains(q) }
    }

    fun filterAndSort(
        songs: List<Song>,
        query: String = "",
        status: SongStatus? = null,
        tuningId: Long? = null,
        sort: SongSort = SongSort.RECENT,
        lastPracticed: Map<Long, Instant> = emptyMap(),
    ): List<Song> {
        val filtered = songs.filter { song ->
            matches(song, query) &&
                (status == null || song.status == status) &&
                (tuningId == null || song.tuningId == tuningId)
        }
        return when (sort) {
            SongSort.TITLE -> filtered.sortedBy { normalize(it.title) }
            SongSort.ADDED -> filtered.sortedByDescending { it.createdAt }
            // Unrated songs sort last rather than as zero: "not yet judged" is
            // not the same as "easy", and putting them first would bury the
            // songs actually being worked on.
            SongSort.DIFFICULTY -> filtered.sortedWith(
                compareByDescending<Song> { it.selfDifficulty ?: Int.MIN_VALUE }
                    .thenBy { normalize(it.title) }
            )
            SongSort.RECENT -> filtered.sortedWith(
                compareByDescending<Song> { lastPracticed[it.id] ?: Instant.EPOCH }
                    .thenByDescending { it.updatedAt }
            )
        }
    }
}
