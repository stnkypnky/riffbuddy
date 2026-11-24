package com.riffbuddy.domain

/**
 * The Practice and Audio settings, and the streak threshold.
 *
 * Stored as plain strings in the settings table, like every other setting;
 * [fromStored] is forgiving so that a hand-edited or half-written value falls
 * back to the default rather than stopping the app from starting.
 */
data class PracticeSettings(
    /**
     * The tuner's reference pitch in hertz, to a tenth: some recordings sit at
     * 441.5 or 442.3, and matching a record means matching it closely.
     */
    val a4Hz: Double = DEFAULT_A4_HZ,
    /** Minutes a week to aim for; null means no goal, which is allowed. */
    val weeklyGoalMinutes: Int? = null,
    /** Minutes a day must reach to count toward a streak. */
    val streakMinutes: Int = Stats.DEFAULT_STREAK_MINUTES,
    /** Keep the screen on while the metronome plays or a session runs. */
    val keepAwake: Boolean = true,
    /**
     * Tab players show tablature only, not standard notation. On by default:
     * most guitarists read tab, not the staff, and a score stacked over the tab
     * doubles the height of every line for something they skip.
     */
    val tabOnly: Boolean = true,
) {
    fun toStored(): Map<String, String> = mapOf(
        // Written as "440" for a whole number, so a value saved here still reads
        // back in an older build that stored whole hertz.
        A4_HZ to NumberEntry.format(a4Hz, A4_DECIMALS),
        WEEKLY_GOAL to (weeklyGoalMinutes?.toString() ?: ""),
        STREAK_MINUTES to streakMinutes.toString(),
        KEEP_AWAKE to keepAwake.toString(),
        TAB_ONLY to tabOnly.toString(),
    )

    companion object {
        const val A4_HZ = "a4_hz"
        const val WEEKLY_GOAL = "weekly_goal_minutes"
        const val STREAK_MINUTES = "streak_minutes"
        const val KEEP_AWAKE = "keep_awake"
        const val TAB_ONLY = "tab_only"
        val KEYS = listOf(A4_HZ, WEEKLY_GOAL, STREAK_MINUTES, KEEP_AWAKE, TAB_ONLY)

        const val DEFAULT_A4_HZ = 440.0
        const val A4_DECIMALS = 1

        /**
         * 415 is baroque pitch, a semitone down; 466 a semitone up. Anything
         * outside that is a different note, not a different A.
         */
        val A4_RANGE = 415.0..466.0
        val STREAK_RANGE = 1..60
        val GOAL_RANGE = 15..1500

        fun fromStored(get: (String) -> String?): PracticeSettings = PracticeSettings(
            // Older builds stored whole hertz ("440"), which reads as a double too.
            a4Hz = get(A4_HZ)?.trim()?.toDoubleOrNull()?.takeIf { it in A4_RANGE }
                ?.let { NumberEntry.round(it, A4_DECIMALS) } ?: DEFAULT_A4_HZ,
            weeklyGoalMinutes = get(WEEKLY_GOAL)?.toIntOrNull()?.takeIf { it in GOAL_RANGE },
            streakMinutes = get(STREAK_MINUTES)?.toIntOrNull()?.takeIf { it in STREAK_RANGE }
                ?: Stats.DEFAULT_STREAK_MINUTES,
            keepAwake = get(KEEP_AWAKE)?.toBooleanStrictOrNull() ?: true,
            tabOnly = get(TAB_ONLY)?.toBooleanStrictOrNull() ?: true,
        )
    }
}
