package com.riffbuddy.domain.gear

/**
 * Gear as the tone card needs to understand it.
 *
 * The point of this file is that the AI is never asked to recall what a piece of
 * gear has. It is handed the list and told to choose from it, because a model
 * asked "what are the Mustang LT25's amp models?" will produce twenty plausible
 * names, most of them wrong, with complete confidence.
 */

/** How a control is reached, which decides whether a tone card may set it. */
enum class Reach {
    /** A knob on the front panel. Turn it and it changes. */
    PANEL,

    /**
     * Only through the amp's display, or the desktop editor.
     *
     * Not the same as "absent". The LT25 has no MID knob, but every amp model
     * has a MIDDLE parameter — so advice to adjust the mids is actionable,
     * provided the card says where to find it.
     */
    DISPLAY,
}

/**
 * Whether a control shapes the sound or just how loud it is.
 *
 * Worth separating because the LT25's panel has both and they look identical.
 * VOLUME is per-preset and part of the tone; MASTER VOLUME is the room. A tone
 * card that prescribes a master volume is telling the player how loud to be in
 * their own house, which is not its business, and makes the card wrong at every other
 * volume.
 */
enum class ControlRole { TONE, LEVEL }

/** Control types: a knob (0–10 or a custom range), a switch, a selector. */
enum class ControlKind(val label: String) { KNOB("Knob"), SWITCH("Switch"), SELECTOR("Selector") }

data class AmpControl(
    val name: String,
    val reach: Reach = Reach.PANEL,
    val role: ControlRole = ControlRole.TONE,
    val min: Double = 0.0,
    val max: Double = 10.0,
    val note: String = "",
    val kind: ControlKind = ControlKind.KNOB,
    /** A selector's positions, in panel order. */
    val options: List<String> = emptyList(),
) {
    /** How the control is set, in words the prompt and the editor both use. */
    val describe: String get() = when (kind) {
        ControlKind.KNOB -> scale
        ControlKind.SWITCH -> "on/off"
        ControlKind.SELECTOR -> if (options.isEmpty()) "selector" else "one of: ${options.joinToString(", ")}"
    }

    val scale: String get() = "${fmt(min)}-${fmt(max)}"
    private fun fmt(v: Double) = if (v == v.toInt().toDouble()) v.toInt().toString() else v.toString()
}

data class AmpModel(val name: String, val description: String)

enum class EffectCategory(val label: String) {
    STOMP("Stompbox"), MOD("Modulation"), DELAY("Delay"), REVERB("Reverb"),
}

data class EffectModel(val name: String, val category: EffectCategory, val description: String)

/**
 * Everything a tone card is allowed to mention about an amp.
 *
 * [slotsPerCategory] is one for the LT25: a preset holds a single stompbox, a
 * single modulation, a single delay and a single reverb. A card suggesting two
 * overdrives describes something the amp cannot be made to do.
 */
data class AmpProfile(
    val make: String,
    val model: String,
    val kind: AmpKind,
    val controls: List<AmpControl>,
    val ampModels: List<AmpModel> = emptyList(),
    val effects: List<EffectModel> = emptyList(),
    val presetSlots: Int = 0,
    val factoryPresets: Int = 0,
    val slotsPerCategory: Int = 1,
    val notes: String = "",
    /** Channels or modes, e.g. Clean, Crunch, Lead. */
    val channels: List<String> = emptyList(),
    /**
     * The app or program that edits presets (Fender Tone, BOSS Tone Studio…),
     * so a tone card can say where a display-only setting lives. Blank when
     * there is none.
     */
    val editorApp: String = "",
) {
    val displayName: String get() = listOf(make, model).filter { it.isNotBlank() }.joinToString(" ")

    /** Controls a tone card may set: the ones that shape sound. */
    val toneControls: List<AmpControl> get() = controls.filter { it.role == ControlRole.TONE }

    val panelControls: List<AmpControl> get() = controls.filter { it.reach == Reach.PANEL }

    fun control(name: String): AmpControl? =
        controls.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun effectsIn(category: EffectCategory): List<EffectModel> = effects.filter { it.category == category }
}

enum class AmpKind { ANALOG, MODELING, HYBRID }

/** A pedal or multi-FX unit: its name, type and controls. */
data class PedalProfile(
    val make: String,
    val model: String,
    val type: String = "",
    val controls: List<String> = emptyList(),
    val notes: String = "",
) {
    val displayName: String get() = listOf(make, model).filter { it.isNotBlank() }.joinToString(" ")
}


/**
 * Gear whose profile was transcribed from the maker's manual or spec, offered
 * as a starting point when adding gear. Anything else is typed in, or drafted
 * by the coach and checked by the player (GearSuggest).
 */
object KnownGear {
    val amps: List<AmpProfile> get() = listOf(MustangLt25.profile)
    val guitars: List<GuitarProfile> get() = listOf(EspLtdEc256.profile)
}
