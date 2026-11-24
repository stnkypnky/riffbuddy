package com.riffbuddy.domain

/**
 * "What did you work on?": a session's time tagged to songs and
 * their parts after the fact.
 */
data class SessionTag(val songId: Long, val partId: Long?, val seconds: Int)

object Tagging {

    /** The notes value that marks an item as a tag, so re-tagging replaces only tags. */
    const val MARK = "tag"

    /**
     * [totalMinutes] shared across [n] things as whole minutes, the remainder
     * going to the first ones, so they always add back up to the total.
     */
    fun split(totalMinutes: Int, n: Int): List<Int> {
        if (n <= 0) return emptyList()
        val total = totalMinutes.coerceAtLeast(0)
        val each = total / n
        val extra = total % n
        return List(n) { i -> each + if (i < extra) 1 else 0 }
    }

    /** The tags already on a session, for editing it. */
    fun of(session: Session): List<SessionTag> =
        session.items.filter { it.notes == MARK && it.songId != null }
            .map { SessionTag(it.songId!!, it.partId, it.seconds) }
}
