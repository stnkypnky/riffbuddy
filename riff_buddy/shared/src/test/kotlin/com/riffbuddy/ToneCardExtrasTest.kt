package com.riffbuddy

import com.riffbuddy.ai.ToneCardDiff
import com.riffbuddy.ai.ToneCardSchema
import com.riffbuddy.ai.ToneCardText
import com.riffbuddy.domain.gear.AmpControl
import com.riffbuddy.domain.gear.AmpKind
import com.riffbuddy.domain.gear.AmpProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The clamp flag, copy-as-text, and version diffs. */
class ToneCardExtrasTest {

    private val amp = AmpProfile(
        "Test", "Amp", AmpKind.ANALOG,
        listOf(AmpControl("Gain"), AmpControl("Presence", min = -5.0, max = 5.0)),
    )

    private fun card(json: String) = ToneCardSchema.validate(JSONObject(json), amp, null)

    @Test
    fun `off-the-dial values are clamped and flagged, in range ones are not`() {
        val c = card("""{"tone_description":"x","confidence":"high","amp_controls":[
            {"name":"Gain","value":12},{"name":"Presence","value":-3}]}""")
        val gain = c.ampControls.first { it.name == "Gain" }
        assertEquals(10.0, gain.value, 0.0)
        assertEquals(12.0, gain.clampedFrom!!, 0.0)
        val presence = c.ampControls.first { it.name == "Presence" }
        assertEquals(-3.0, presence.value, 0.0)
        assertNull(presence.clampedFrom)
        // The real ends travel with the setting, negative start included.
        assertEquals(-5.0, presence.min, 0.0)
    }

    @Test
    fun `copied text has every number beside its name and says what was clamped`() {
        val c = card("""{"tone_description":"Warm crunch","confidence":"high","amp_controls":[{"name":"Gain","value":12}],
            "playing_tips":["Dig in"]}""")
        val text = ToneCardText.format(c, "Floods — solo tone")
        assertTrue(text.startsWith("Floods — solo tone\nWarm crunch"))
        assertTrue(text.contains("Gain: 10 — the coach said 12, off the dial"))
        assertTrue(text.contains("· Dig in"))
    }

    @Test
    fun `a diff names what moved, and only that`() {
        val v1 = """{"amp_model":"British 60s","amp_controls":[{"name":"Gain","value":6},{"name":"Treble","value":7},{"name":"Bass","value":5}],
            "effects":[{"model":"Small Hall"}]}"""
        val v2 = """{"amp_model":"British 60s","amp_controls":[{"name":"Gain","value":4},{"name":"Treble","value":7},{"name":"Middle","value":6}],
            "effects":[{"model":"Small Hall"},{"model":"Tape Delay"}]}"""
        assertEquals(
            listOf("Gain 6 → 4", "Bass no longer set", "Middle set to 6", "Tape Delay added"),
            ToneCardDiff.changes(v1, v2),
        )
        assertEquals(emptyList<String>(), ToneCardDiff.changes(v1, v1))
    }
}
