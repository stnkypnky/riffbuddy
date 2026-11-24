package com.riffbuddy.domain

/**
 * "About you": level, styles and goals. They feed every AI prompt, which
 * until now said "intermediate, rock and blues" for everyone.
 */
data class PlayerProfile(
    val level: Level = Level.INTERMEDIATE,
    val styles: List<String> = emptyList(),
    val goals: List<String> = emptyList(),
) {
    enum class Level(val label: String, val forPrompt: String) {
        BEGINNER("Beginner", "beginner — reads tab, not standard notation; explain with frets and strings"),
        INTERMEDIATE("Intermediate", "intermediate"),
        ADVANCED("Advanced", "advanced"),
    }

    val stylesForPrompt: String get() = styles.ifEmpty { listOf("not specified") }.joinToString(", ")

    fun toStored(): Map<String, String> = mapOf(
        LEVEL to level.name,
        STYLES to styles.joinToString(","),
        GOALS to goals.joinToString(","),
    )

    companion object {
        const val LEVEL = "player_level"
        const val STYLES = "player_styles"
        const val GOALS = "player_goals"
        const val ONBOARDED = "onboarded"
        val KEYS = listOf(LEVEL, STYLES, GOALS)

        val STYLE_CHOICES = listOf("rock", "blues", "metal", "hard rock", "punk", "funk", "pop", "country", "jazz", "acoustic")
        val GOAL_CHOICES = listOf("learn songs", "play faster", "improvise", "understand theory", "play with others", "write riffs")

        fun fromStored(get: (String) -> String?): PlayerProfile = PlayerProfile(
            level = get(LEVEL)?.let { runCatching { Level.valueOf(it) }.getOrNull() } ?: Level.INTERMEDIATE,
            styles = get(STYLES)?.let(::list) ?: emptyList(),
            goals = get(GOALS)?.let(::list) ?: emptyList(),
        )

        private fun list(text: String) = text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }
}
