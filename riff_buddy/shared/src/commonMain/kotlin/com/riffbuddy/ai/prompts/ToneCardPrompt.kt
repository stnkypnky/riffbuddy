package com.riffbuddy.ai.prompts

import com.riffbuddy.domain.gear.AmpProfile
import com.riffbuddy.domain.gear.PedalProfile
import com.riffbuddy.domain.gear.ControlRole
import com.riffbuddy.domain.gear.EffectCategory
import com.riffbuddy.domain.gear.GuitarProfile
import com.riffbuddy.domain.gear.Reach

/**
 * The tone card prompt, versioned.
 *
 * Bump [VERSION] on any change to the wording. It is part of the cache key, so
 * a bump invalidates every card the old wording produced — which is the point:
 * cards from two different prompts should not sit side by side pretending to be
 * comparable.
 */
object ToneCardPrompt {

    const val VERSION = "tone-v2"

    /**
     * The system prompt states the rig's capabilities as a closed world.
     *
     * The schema already constrains a provider that honors schemas, but saying
     * it in words as well does two things the schema cannot: it stops a model
     * *reasoning* its way to advice about gear that is not there ("add a tube
     * screamer in front"), and it gives the model the vocabulary to say it is
     * unsure rather than inventing something to fill a required field.
     */
    fun system(
        amp: AmpProfile,
        guitar: GuitarProfile?,
        level: String,
        styles: String,
        pedals: List<PedalProfile> = emptyList(),
    ): String =
        buildString {
            appendLine("You set up guitar tones for one specific rig. Be concrete and brief.")
            appendLine()
            appendLine("THE RIG — this is everything it can do. Nothing else exists.")
            guitar?.let {
                appendLine("Guitar: ${it.displayName}, ${it.stringCount} strings.")
                appendLine("  Pickup positions: ${it.pickups.joinToString(", ") { p -> p.label }}")
                appendLine("  Controls: ${it.controls.joinToString(", ")}")
                if (!it.hasCoilSplit) appendLine("  No coil-split. Do not suggest splitting a pickup.")
            }
            appendLine("Amp: ${amp.displayName} (${amp.kind.name.lowercase()}).")

            val panel = amp.toneControls.filter { it.reach == Reach.PANEL }
            val display = amp.toneControls.filter { it.reach == Reach.DISPLAY }
            // Each control with its own range or type: since the gear editor,
            // an amp's knobs need not run 0-10, and a switch is not a knob.
            appendLine("  Panel controls you may set: ${panel.joinToString(", ") { "${it.name} (${it.describe})" }}")
            if (display.isNotEmpty()) {
                val where = if (amp.editorApp.isNotBlank()) "the amp's display, or the ${amp.editorApp} app"
                else "the amp's display or its editor app"
                appendLine(
                    "  Also settable, but not on the panel — say where: " +
                        display.joinToString(", ") { it.name } + " (in $where)"
                )
            }
            if (amp.channels.isNotEmpty()) {
                appendLine("  Channels — pick one by name: ${amp.channels.joinToString(", ")}")
            }
            amp.controls.filter { it.role == ControlRole.LEVEL }.forEach {
                appendLine("  ${it.name} is loudness, not tone. Never prescribe it.")
            }

            if (amp.ampModels.isNotEmpty()) {
                appendLine()
                appendLine("  Amp models — choose exactly one, by this exact name:")
                amp.ampModels.forEach { appendLine("    ${it.name} — ${it.description}") }
            }

            if (amp.effects.isNotEmpty()) {
                appendLine()
                if (amp.slotsPerCategory <= 1) {
                    appendLine("  Effects. A preset holds ONE of each category. Use a category at most once.")
                } else {
                    appendLine("  Effects. A preset holds up to ${amp.slotsPerCategory} of each category.")
                }
                EffectCategory.entries.forEach { category ->
                    val inCategory = amp.effects.filter { it.category == category }
                    if (inCategory.isNotEmpty()) {
                        appendLine("    ${category.label}: ${inCategory.joinToString(", ") { it.name }}")
                    }
                }
            }

            if (pedals.isNotEmpty()) {
                appendLine()
                appendLine("  Pedals, in front of the amp, in this order:")
                pedals.forEach { p ->
                    appendLine("    ${p.displayName}${if (p.type.isNotBlank()) " (${p.type})" else ""}" +
                        if (p.controls.isNotEmpty()) " — controls: ${p.controls.joinToString(", ")}" else "")
                }
            }

            appendLine()
            appendLine("THE PLAYER: $level. Styles: $styles.")
            appendLine()
            appendLine("RULES")
            appendLine("1. Use only the names listed above, spelled exactly as written.")
            appendLine("2. If you are unsure what a record used, say so in assumptions and give")
            appendLine("   a sensible starting point anyway. Never invent gear to sound certain.")
            appendLine("3. Do not reproduce lyrics, tab or notation.")
            appendLine("4. This is a starting point to be finished by ear, and should say so.")
            appendLine("5. Six playing tips at most. Fewer is better.")
        }

    /** The song and part being asked about. */
    fun user(
        title: String,
        artist: String,
        part: String,
        tuning: String?,
        capo: Int,
        sectionNote: String?,
        tweak: String?,
    ): String = buildString {
        append("Set up a tone for \"$title\"")
        if (artist.isNotBlank()) append(" by $artist")
        append(" — the $part part.")
        tuning?.takeIf { it.isNotBlank() }?.let { append(" Tuning: $it.") }
        if (capo > 0) append(" Capo at fret $capo.")
        sectionNote?.takeIf { it.isNotBlank() }?.let { append(" Section: $it.") }
        tweak?.takeIf { it.isNotBlank() }?.let { append(" Adjust from the last version: $it.") }
    }

    /**
     * Refining a card. The previous card goes back in so the model changes
     * what was complained about and leaves the rest alone — a refinement that
     * silently re-rolls everything is not a refinement, and would undo settings
     * the player was happy with.
     */
    fun refinement(previousCardJson: String, feedback: String): String = buildString {
        appendLine("Here is the tone card you gave, as JSON:")
        appendLine(previousCardJson)
        appendLine()
        appendLine("The player says: $feedback")
        appendLine()
        appendLine("Return the whole card again with only what that feedback implies changed.")
        appendLine("Leave every other value exactly as it was.")
    }
}
