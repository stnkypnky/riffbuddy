package com.riffbuddy.domain.gear

/**
 * The Fender Mustang LT25, transcribed from Fender's own expanded owner's
 * manual (OM_2311100000_Mustang_LT25_English) rather than recalled.
 *
 * Curated reference data for one known amp's control layout. It
 * exists so the tone card's prompt can hand the model a closed list and say
 * "choose from these" — asked to remember the LT25's amp models unaided, an LLM
 * will invent twenty plausible names and be wrong about most of them.
 *
 * Verified against the manual: five panel knobs (page 2), twenty amp models
 * (page 8), twenty-six effects in four categories (pages 13-14), thirty factory
 * presets in sixty slots (page 7).
 */
object MustangLt25 {

    val profile = AmpProfile(
        make = "Fender",
        model = "Mustang LT25",
        kind = AmpKind.MODELING,
        presetSlots = 60,
        factoryPresets = 30,
        slotsPerCategory = 1,
        editorApp = "Fender Tone",
        notes = "25W modeling combo. Presets are edited on the amp with the encoder, " +
            "or over USB in the Fender Tone desktop app. A single-button footswitch is optional.",
        controls = listOf(
            // Panel order, left to right, exactly as the manual's diagram labels
            // them. The card's faceplate is drawn in this order because that is
            // what the player is looking at.
            AmpControl("Gain", Reach.PANEL, note = "Gain setting within the current preset."),
            AmpControl("Volume", Reach.PANEL, note = "The preset's own level — part of its sound."),
            AmpControl("Treble", Reach.PANEL),
            AmpControl("Bass", Reach.PANEL),
            AmpControl(
                "Master Volume", Reach.PANEL, role = ControlRole.LEVEL,
                note = "Overall loudness in the room. Not part of any preset's tone.",
            ),
            // No MID knob on the panel — but every amp model has one in its
            // parameter list, so "bring the mids up" is advice the player can act on.
            AmpControl(
                "Middle", Reach.DISPLAY,
                note = "No knob for this. Press the encoder on the amp model, or use Fender Tone.",
            ),
        ),

        ampModels = listOf(
            AmpModel("50s Twin", "1957 Twin — 2x12 tweed, clean to dirty"),
            AmpModel("60s UK Cln", "Vox AC30 — British Invasion clean and dirty"),
            AmpModel("70s Rock", "Late-60s/early-70s Marshall Super Lead"),
            AmpModel("70s UK Cln", "100-watt Hiwatt DR103 — cleaner British stack"),
            AmpModel("80s Rock", "Marshall JCM800 — quintessential 80s metal"),
            AmpModel("90s Rock", "Mesa Dual Rectifier — nu-metal distortion"),
            AmpModel("Bassman", "'59 Bassman tweed"),
            AmpModel("Burn", "Fender Super-Sonic burn channel — cascading gain, long sustain"),
            AmpModel("Champ", "'57 Champ — small late-50s Fender recording amp"),
            AmpModel("Deluxe Cln", "1965 Deluxe Reverb"),
            AmpModel("Deluxe Dirt", "1957 Deluxe tweed — thick, compressed overdrive"),
            AmpModel("Doom Metal", "Orange OR120 — sludgy"),
            AmpModel("Excelsior", "Modern Fender oddity with a 15-inch thump"),
            AmpModel("Alt Metal", "Mesa Dual Rectifier, gated with a fast attenuation curve"),
            AmpModel("Metal 2000", "EVH 5150 III — modern high gain"),
            AmpModel("Princeton", "1965 Princeton — snappy single 10-inch"),
            AmpModel("Smalltone", "1960s Sears Silvertone — garage classic"),
            AmpModel("Super Clean", "Direct-to-desk studio preamp, uncolored"),
            AmpModel("Super Heavy", "EVH 5150 III, gated — high gain"),
            AmpModel("Twin Clean", "1965 Twin Reverb — the Fender clean"),
        ),

        effects = listOf(
            EffectModel("Overdrive", EffectCategory.STOMP, "Fender overdrive built for the LT25"),
            EffectModel("Blues Drive", EffectCategory.STOMP, "Ibanez TS808 Tube Screamer"),
            EffectModel("Myth Drive", EffectCategory.STOMP, "90s Klon Centaur"),
            EffectModel("Rock Dirt", EffectCategory.STOMP, "Pro Co RAT"),
            EffectModel("Fuzz", EffectCategory.STOMP, "Fender fuzz with variable low end"),
            EffectModel("Big Fuzz", EffectCategory.STOMP, "Electro-Harmonix Big Muff"),
            EffectModel("Octobot", EffectCategory.STOMP, "Octave-down plus octave-up fuzz"),
            EffectModel("Compressor", EffectCategory.STOMP, "MXR Dyna Comp"),
            EffectModel("Sustain", EffectCategory.STOMP, "MXR M-163 — strong compression, short attack"),
            EffectModel("Metal Gate", EffectCategory.STOMP, "Noise gate, fast attenuation for high gain"),
            EffectModel("5-Band EQ", EffectCategory.STOMP, "Five-band graphic equaliser"),

            EffectModel("Chorus", EffectCategory.MOD, "Triangle-wave chorus"),
            EffectModel("Flanger", EffectCategory.MOD, "Triangle-wave flanger"),
            EffectModel("Vibratone", EffectCategory.MOD, "Rotating speaker baffle"),
            EffectModel("Tremolo", EffectCategory.MOD, "Tube bias tremolo, as in a Princeton Reverb"),
            EffectModel("Phaser", EffectCategory.MOD, "The jetliner whoosh"),
            EffectModel("Step Filter", EffectCategory.MOD, "Rhythmically choppy stepped filter"),
            EffectModel("Touch Wah", EffectCategory.MOD, "Wah driven by picking dynamics"),

            // The TAP button and tap light work with this category only.
            EffectModel("Delay", EffectCategory.DELAY, "Clean, simple repeats"),
            EffectModel("Reverse", EffectCategory.DELAY, "Backwards guitar"),
            EffectModel("Echo", EffectCategory.DELAY, "Maestro Echoplex tape delay, wow and flutter"),

            EffectModel("Large Hall", EffectCategory.REVERB, "Big, bright, cavernous"),
            EffectModel("Small Room", EffectCategory.REVERB, "Warmer, less echoey"),
            EffectModel("Spring 65", EffectCategory.REVERB, "The Fender spring reverb"),
            EffectModel("Plate", EffectCategory.REVERB, "EMT 140 plate"),
            EffectModel("Arena", EffectCategory.REVERB, "Long-trailing stadium reverb"),
        ),
    )
}
