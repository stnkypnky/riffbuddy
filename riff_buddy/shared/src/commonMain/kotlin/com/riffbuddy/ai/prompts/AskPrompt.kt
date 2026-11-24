package com.riffbuddy.ai.prompts

import com.riffbuddy.domain.Song
import com.riffbuddy.domain.gear.AmpProfile
import com.riffbuddy.domain.gear.GuitarProfile

/**
 * Free-form questions to the coach.
 *
 * Not everything worth asking is about a song. "What's a good warm-up for
 * stretching?", "Which pickup for a funk part?", "Why does my B string go sharp
 * when I bend?" — none of those fit a tone card, and forcing them to would make
 * the coach useless for the questions people actually have.
 */
object AskPrompt {

    const val VERSION = "ask-v2"

    /** How long an answer should be. Chosen in Ask, remembered in settings. */
    enum class Length(val label: String, val instruction: String) {
        SHORT("Short", "Answer in two or three sentences. Only what matters most."),
        STANDARD("Standard", "Be direct and specific. A short paragraph or a few bullet points, not an essay."),
        IN_DEPTH(
            "In depth",
            "Give a thorough answer: explain the why as well as the what, walk through it step by step, " +
                "and include an example or a short exercise to practice it. Use headings and lists where they help.",
        );

        companion object {
            fun fromName(name: String?) = entries.firstOrNull { it.name == name } ?: STANDARD
        }
    }

    /** Which context the player has chosen to send. */
    data class Context(
        val includeRig: Boolean = true,
        val includeSong: Boolean = false,
        val includeLevel: Boolean = true,
    )

    fun system(
        context: Context,
        amp: AmpProfile?,
        guitar: GuitarProfile?,
        song: Song?,
        level: String,
        styles: String,
        length: Length = Length.STANDARD,
        webSearch: Boolean = false,
    ): String = buildString {
        appendLine("You are a guitar teacher answering a practice-room question.")
        appendLine(length.instruction)
        appendLine("Format with Markdown: **bold**, bullet or numbered lists, and short headings (##) when they help.")
        // gpt-oss answered "I'm sorry, but I can't help with that" to a question
        // about current firmware it couldn't know (2026-09-27).
        appendLine("Never refuse a guitar or music question. If it needs something you can't know (current")
        appendLine("versions, prices, news), say so, give what you do know, and say where to check.")
        if (webSearch) {
            appendLine("You can search the web. Use it for anything current or specific (gear, firmware, releases,")
            appendLine("prices, a song's details). Say what you found and where; don't guess past what the sources say.")
        }
        appendLine("If something depends on gear or taste, say so rather than pretending there is one answer.")
        appendLine("Never reproduce lyrics, tab or notation.")

        if (context.includeRig && (amp != null || guitar != null)) {
            appendLine()
            appendLine("Their rig, if it is relevant — do not mention it otherwise:")
            guitar?.let { appendLine("  Guitar: ${it.displayName}, pickups: ${it.pickups.joinToString(", ") { p -> p.label }}") }
            amp?.let {
                appendLine("  Amp: ${it.displayName}")
                appendLine("  Panel knobs: ${it.toneControls.joinToString(", ") { c -> c.name }}")
                if (it.ampModels.isNotEmpty()) {
                    appendLine("  Amp models available: ${it.ampModels.joinToString(", ") { m -> m.name }}")
                }
            }
            appendLine("  Only name gear from this list. Do not suggest buying anything.")
        }

        if (context.includeSong && song != null) {
            appendLine()
            append("They are working on \"${song.title}\"")
            if (song.artist.isNotBlank()) append(" by ${song.artist}")
            appendLine(".")
        }

        if (context.includeLevel) {
            appendLine()
            appendLine("They play at a $level level. Styles: $styles.")
        }
    }.trim()

    /**
     * Exactly what would be sent, for the "what I'm sending" panel.
     *
     * The privacy promise is only checkable if the player can see the
     * payload, and it is only true if this function and the real request are
     * built from the same code — which they are.
     */
    fun preview(systemPrompt: String, question: String, history: List<com.riffbuddy.ai.ChatTurn> = emptyList(), webSearch: Boolean = false): String =
        buildString {
            append("— Context sent —\n$systemPrompt\n\n")
            if (history.isNotEmpty()) {
                append("— Earlier in this chat (${history.size} messages) —\n")
                history.forEach { append(if (it.fromPlayer) "You: " else "Coach: ").append(it.text.take(300)).append("\n\n") }
            }
            append("— Your question —\n$question")
            if (webSearch) append("\n\n— Web search —\nOn: the AI provider searches the web for this question.")
        }
}
