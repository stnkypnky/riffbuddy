package com.riffbuddy

import com.riffbuddy.ai.prompts.ToneCardPrompt
import com.riffbuddy.data.GearJson
import com.riffbuddy.domain.gear.AmpControl
import com.riffbuddy.domain.gear.AmpKind
import com.riffbuddy.domain.gear.AmpProfile
import com.riffbuddy.domain.gear.ControlKind
import com.riffbuddy.domain.gear.MustangLt25
import com.riffbuddy.domain.gear.PedalProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gear survives the database, and the tone prompt says what it is. */
class GearJsonTest {

    @Test
    fun `the LT25's controls survive the controls column unchanged`() {
        // The prompt is built from what is read back, so a lossy round trip
        // would quietly change every tone card.
        val controls = MustangLt25.profile.controls
        assertEquals(controls, GearJson.controls(GearJson.controls(controls)))
    }

    @Test
    fun `knobs, custom ranges, switches and selectors round-trip`() {
        val controls = listOf(
            AmpControl("Gain"),
            AmpControl("Presence", min = -5.0, max = 5.0),
            AmpControl("Bright", kind = ControlKind.SWITCH),
            AmpControl("Voice", kind = ControlKind.SELECTOR, options = listOf("Clean", "Crunch", "Lead")),
        )
        assertEquals(controls, GearJson.controls(GearJson.controls(controls)))
    }

    @Test
    fun `a row written before the editor reads as knobs`() {
        val old = """[{"name":"Gain","reach":"PANEL","role":"TONE","min":0,"max":10,"note":""}]"""
        val read = GearJson.controls(old).single()
        assertEquals(ControlKind.KNOB, read.kind)
        assertEquals(emptyList<String>(), read.options)
    }

    @Test
    fun `junk in a column reads as nothing, not a crash`() {
        assertEquals(emptyList<AmpControl>(), GearJson.controls("not json"))
        assertEquals(emptyList<String>(), GearJson.strings(null))
        assertEquals(listOf(3L, 7L), GearJson.ids("3, 7,x"))
    }

    @Test
    fun `seeded effect objects read back as their names`() {
        assertEquals(listOf("Overdrive"), GearJson.strings("""[{"name":"Overdrive","category":"STOMP"}]"""))
    }

    @Test
    fun `the prompt gives each control its own range and names pedals and channels`() {
        val amp = AmpProfile(
            make = "Marshall", model = "DSL20", kind = AmpKind.ANALOG,
            controls = listOf(
                AmpControl("Gain"),
                AmpControl("Presence", min = -5.0, max = 5.0),
                AmpControl("Tone shift", kind = ControlKind.SWITCH),
            ),
            channels = listOf("Classic Gain", "Ultra Gain"),
        )
        val pedal = PedalProfile("Ibanez", "TS9", "Overdrive", listOf("Drive", "Tone", "Level"))
        val prompt = ToneCardPrompt.system(amp, null, "beginner", "rock", listOf(pedal))
        assertTrue(prompt.contains("Gain (0-10)"))
        assertTrue(prompt.contains("Presence (-5-5)"))
        assertTrue(prompt.contains("Tone shift (on/off)"))
        assertTrue(prompt.contains("Classic Gain, Ultra Gain"))
        assertTrue(prompt.contains("Ibanez TS9 (Overdrive) — controls: Drive, Tone, Level"))
        assertFalse("Fender's app named for a Marshall", prompt.contains("Fender Tone"))
    }
}

class GearSuggestTest {
    @Test
    fun `a suggestion is read defensively`() {
        val reply = org.json.JSONObject(
            """{"controls":[
                {"name":"Gain","type":"knob","min":0,"max":10},
                {"name":"gain","type":"knob"},
                {"name":"","type":"knob"},
                {"name":"Presence","type":"knob","min":5,"max":-5},
                {"name":"Voice","type":"selector","options":["Clean","Lead","Clean"]},
                {"name":"Bright","type":"switch"}],
              "channels":["Clean","Lead"],"confident":false,"note":"Two revisions exist"}"""
        )
        val s = com.riffbuddy.ai.GearSuggest.parse(reply)
        assertEquals(listOf("Gain", "Presence", "Voice", "Bright"), s.controls.map { it.name })
        assertEquals(-5.0, s.controls[1].min, 0.0)
        assertEquals(5.0, s.controls[1].max, 0.0)
        assertEquals(listOf("Clean", "Lead"), s.controls[2].options)
        assertEquals(ControlKind.SWITCH, s.controls[3].kind)
        assertFalse(s.confident)
    }

    @Test
    fun `never more than the faceplate can draw`() {
        val many = (1..30).joinToString(",") { """{"name":"K$it","type":"knob"}""" }
        val s = com.riffbuddy.ai.GearSuggest.parse(org.json.JSONObject("""{"controls":[$many],"confident":true}"""))
        assertEquals(16, s.controls.size)
    }
}

/**
 * Anyone's gear gets what the LT25 has: the coach drafts amp models, effects,
 * which knob is loudness and which settings live in the display.
 */
class GearDraftTest {

    @Test
    fun `an amp draft carries everything a tone card uses`() {
        val reply = org.json.JSONObject(
            """{"controls":[
                {"name":"Gain","type":"knob","role":"tone","reach":"panel"},
                {"name":"Master","type":"knob","role":"level","reach":"panel"},
                {"name":"Mid","type":"knob","reach":"display"}],
              "ampModels":[{"name":"Brown","description":"Hot-rodded British"},{"name":"brown"},{"name":""}],
              "effects":[{"name":"Blues Driver","category":"stomp"},{"name":"Chorus","category":"mod"},
                         {"name":"Tape","category":"delay"},{"name":"Spring","category":"reverb"},
                         {"name":"Odd","category":"nonsense"}],
              "effectsPerCategory":2,"editorApp":"BOSS Tone Studio","confident":true,"note":""}"""
        )
        val s = com.riffbuddy.ai.GearSuggest.parse(reply)
        assertEquals(com.riffbuddy.domain.gear.ControlRole.LEVEL, s.controls[1].role)
        assertEquals(com.riffbuddy.domain.gear.Reach.DISPLAY, s.controls[2].reach)
        assertEquals(listOf("Brown"), s.ampModels.map { it.name })
        assertEquals("Hot-rodded British", s.ampModels[0].description)
        assertEquals(5, s.effects.size)
        // An unknown category is kept as a stompbox rather than lost.
        assertEquals(com.riffbuddy.domain.gear.EffectCategory.STOMP, s.effects.last().category)
        assertEquals(2, s.effectsPerCategory)
        assertEquals("BOSS Tone Studio", s.editorApp)
    }

    @Test
    fun `a guitar draft keeps what each pickup sounds like, and old replies still read`() {
        val s = com.riffbuddy.ai.GearSuggest.parse(org.json.JSONObject(
            """{"controls":[],"pickups":[{"position":"Bridge","description":"humbucker, bright"},{"position":"Neck"}],"confident":true}"""
        ))
        assertEquals(listOf("Bridge", "Neck"), s.pickups.map { it.label })
        assertEquals("humbucker, bright", s.pickups[0].description)
        val old = com.riffbuddy.ai.GearSuggest.parse(org.json.JSONObject(
            """{"controls":[],"pickups":["Bridge","Middle"],"confident":true}"""
        ))
        assertEquals(listOf("Bridge", "Middle"), old.pickups.map { it.label })
    }

    @Test
    fun `only the fields a kind of gear has are asked for`() {
        val amp = com.riffbuddy.ai.GearSuggest.schema(com.riffbuddy.ai.GearSuggest.Kind.AMP).getJSONObject("properties")
        val pedal = com.riffbuddy.ai.GearSuggest.schema(com.riffbuddy.ai.GearSuggest.Kind.PEDAL).getJSONObject("properties")
        assertTrue(amp.has("ampModels") && amp.has("effects") && amp.has("editorApp"))
        assertFalse(pedal.has("channels") || pedal.has("ampModels") || pedal.has("pickups"))
    }

    @Test
    fun `names with descriptions round-trip, and plain names stored before still read`() {
        val list = listOf("Bridge" to "Humbucker", "Neck" to "")
        assertEquals(list, GearJson.named(GearJson.named(list)))
        assertEquals(listOf("Bridge" to "", "Neck" to ""), GearJson.named("""["Bridge","Neck"]"""))
        assertEquals(emptyList<Pair<String, String>>(), GearJson.named("not json"))
    }

    @Test
    fun `the tone prompt names the amp's own editor app and effect limits`() {
        val amp = AmpProfile(
            "BOSS", "Katana", AmpKind.MODELING,
            listOf(AmpControl("Gain"), AmpControl("Mid", reach = com.riffbuddy.domain.gear.Reach.DISPLAY)),
            effects = listOf(com.riffbuddy.domain.gear.EffectModel("Chorus", com.riffbuddy.domain.gear.EffectCategory.MOD, "")),
            editorApp = "BOSS Tone Studio", slotsPerCategory = 2,
        )
        val prompt = ToneCardPrompt.system(amp, null, "beginner", "rock")
        assertTrue(prompt.contains("in the amp's display, or the BOSS Tone Studio app"))
        assertTrue(prompt.contains("up to 2 of each category"))
        // The LT25 still gets "one of each".
        assertTrue(ToneCardPrompt.system(MustangLt25.profile, null, "beginner", "rock").contains("ONE of each category"))
    }
}
