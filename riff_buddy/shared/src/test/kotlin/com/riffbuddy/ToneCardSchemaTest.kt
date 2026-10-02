package com.riffbuddy

import com.riffbuddy.ai.ToneCardSchema
import com.riffbuddy.domain.gear.EffectCategory
import com.riffbuddy.domain.gear.EspLtdEc256
import com.riffbuddy.domain.gear.MustangLt25
import com.riffbuddy.domain.gear.Reach
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToneCardSchemaTest {

    private val amp = MustangLt25.profile
    private val guitar = EspLtdEc256.profile

    private fun validate(body: String) =
        ToneCardSchema.validate(JSONObject(body), amp, guitar)

    // ---- the schema itself -------------------------------------------------

    /**
     * The enums are the rig's real capabilities, which is what makes an invented
     * amp model structurally impossible for a provider that honors schemas.
     */
    @Test
    fun `the schema offers only this amp's models and controls`() {
        val schema = ToneCardSchema.forRig(amp, guitar)
        val props = schema.getJSONObject("properties")

        val models = props.getJSONObject("amp_model").getJSONArray("enum")
        assertEquals(20, models.length())

        val controlNames = props.getJSONObject("amp_controls")
            .getJSONObject("items").getJSONObject("properties")
            .getJSONObject("name").getJSONArray("enum")
        val names = (0 until controlNames.length()).map { controlNames.getString(it) }
        assertTrue(names.contains("Gain"))
        assertTrue("Middle is reachable and must be offerable", names.contains("Middle"))
        assertTrue("Master Volume is loudness, not tone", !names.contains("Master Volume"))

        val pickups = props.getJSONObject("pickup").getJSONArray("enum")
        assertEquals(3, pickups.length())
    }

    // ---- validation --------------------------------------------------------

    @Test
    fun `a good card comes through intact`() {
        val card = validate(
            """
            {"tone_description":"Tight crunch",
             "amp_model":"80s Rock",
             "pickup":"Bridge",
             "amp_controls":[{"name":"Gain","value":6},{"name":"Bass","value":5}],
             "effects":[{"category":"REVERB","model":"Spring 65","purpose":"space"}],
             "playing_tips":["Palm mute the low strings"],
             "confidence":"high"}
            """.trimIndent()
        )
        assertEquals("80s Rock", card.ampModel)
        assertEquals("Bridge", card.pickup)
        assertEquals(2, card.ampControls.size)
        assertEquals(1, card.effects.size)
        assertEquals("high", card.confidence)
        assertTrue(card.unavailable.isEmpty())
    }

    /**
     * The failure this whole design exists to prevent. A confident, plausible,
     * non-existent amp model is worse than no card, because the player would go
     * looking for it.
     */
    @Test
    fun `an invented amp model is rejected and reported`() {
        val card = validate(
            """{"tone_description":"x","amp_model":"British Plexi Gold","amp_controls":[]}"""
        )
        assertNull(card.ampModel)
        assertTrue(card.unavailable.any { it.contains("British Plexi Gold") })
    }

    @Test
    fun `a control this amp does not have is moved to unavailable`() {
        val card = validate(
            """
            {"tone_description":"x",
             "amp_controls":[{"name":"Presence","value":7},{"name":"Gain","value":5}]}
            """.trimIndent()
        )
        assertEquals(1, card.ampControls.size)
        assertEquals("Gain", card.ampControls.first().name)
        assertTrue(card.unavailable.any { it.contains("Presence") })
    }

    /** Master is loudness. Accepting it would make the card wrong at every other volume. */
    @Test
    fun `master volume is refused as a tone setting`() {
        val card = validate(
            """{"tone_description":"x","amp_controls":[{"name":"Master Volume","value":4}]}"""
        )
        assertTrue(card.ampControls.isEmpty())
        assertTrue(card.unavailable.any { it.contains("loudness") })
    }

    /** Middle has no knob but is reachable, so it belongs on the card with a note. */
    @Test
    fun `middle is accepted and carries where to find it`() {
        val card = validate(
            """{"tone_description":"x","amp_controls":[{"name":"Middle","value":6.5}]}"""
        )
        val middle = card.ampControls.single()
        assertEquals("Middle", middle.name)
        assertEquals(Reach.DISPLAY, middle.reach)
        assertTrue(middle.note.contains("Fender Tone"))
    }

    @Test
    fun `values outside the dial are clamped and flagged`() {
        val card = validate(
            """{"tone_description":"x","amp_controls":[{"name":"Gain","value":14}]}"""
        )
        assertEquals(10.0, card.ampControls.single().value, 0.001)
        // Flagged as a field the faceplate shows ("said 14"), not buried in the note.
        assertEquals(14.0, card.ampControls.single().clampedFrom!!, 0.001)
    }

    /** A preset holds one effect per category; a second is not a thing the amp can do. */
    @Test
    fun `a second effect in the same category is refused`() {
        val card = validate(
            """
            {"tone_description":"x","amp_controls":[],
             "effects":[
               {"category":"STOMP","model":"Blues Drive"},
               {"category":"STOMP","model":"Rock Dirt"},
               {"category":"REVERB","model":"Plate"}]}
            """.trimIndent()
        )
        assertEquals(2, card.effects.size)
        assertEquals(setOf(EffectCategory.STOMP, EffectCategory.REVERB), card.effects.map { it.category }.toSet())
        assertTrue(card.unavailable.any { it.contains("only one stompbox") })
    }

    @Test
    fun `an effect this amp does not have is refused`() {
        val card = validate(
            """{"tone_description":"x","amp_controls":[],"effects":[{"category":"STOMP","model":"Klon Centaur"}]}"""
        )
        assertTrue(card.effects.isEmpty())
        assertTrue(card.unavailable.any { it.contains("Klon Centaur") })
    }

    /** Tips are capped at six; more is a lecture nobody reads with a guitar on. */
    @Test
    fun `playing tips are capped at six`() {
        val tips = (1..12).joinToString(",") { "\"tip $it\"" }
        val card = validate(
            """{"tone_description":"x","amp_controls":[],"playing_tips":[$tips]}"""
        )
        assertEquals(6, card.playingTips.size)
    }

    /**
     * A card that is right about nine things and invented a tenth is still
     * worth showing. Rejecting it whole would spend another free-tier request
     * to punish one field.
     */
    @Test
    fun `a partly wrong card still yields everything that was right`() {
        val card = validate(
            """
            {"tone_description":"Warm clean",
             "amp_model":"Not A Real Amp",
             "amp_controls":[{"name":"Gain","value":3},{"name":"Presence","value":5}],
             "effects":[{"category":"REVERB","model":"Large Hall"}],
             "confidence":"medium"}
            """.trimIndent()
        )
        assertEquals("Warm clean", card.toneDescription)
        assertEquals(1, card.ampControls.size)
        assertEquals(1, card.effects.size)
        assertEquals(2, card.unavailable.size)
    }

    @Test
    fun `nonsense confidence falls back to medium`() {
        assertEquals("medium", validate("""{"tone_description":"x","confidence":"certain"}""").confidence)
        assertEquals("low", validate("""{"tone_description":"x","confidence":"low"}""").confidence)
    }

    @Test
    fun `an empty reply does not throw`() {
        val card = validate("{}")
        assertEquals("", card.toneDescription)
        assertTrue(card.ampControls.isEmpty())
    }
}
