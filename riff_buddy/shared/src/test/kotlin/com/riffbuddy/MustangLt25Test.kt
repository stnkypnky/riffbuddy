package com.riffbuddy

import com.riffbuddy.domain.gear.ControlRole
import com.riffbuddy.domain.gear.EffectCategory
import com.riffbuddy.domain.gear.MustangLt25
import com.riffbuddy.domain.gear.Reach
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the LT25 reference data to what Fender's manual actually says.
 *
 * Not busywork: this data exists precisely so the tone card never asks a model
 * to remember an amp's capabilities. If it drifts from the manual, the prompt
 * starts handing over a closed list that is quietly wrong, which is worse than
 * having no list at all — the output would look authoritative either way.
 */
class MustangLt25Test {

    private val amp = MustangLt25.profile

    /** Five knobs, in panel order, page 2 of the manual. */
    @Test
    fun `the panel has exactly the five knobs it has`() {
        assertEquals(
            listOf("Gain", "Volume", "Treble", "Bass", "Master Volume"),
            amp.panelControls.map { it.name },
        )
    }

    /**
     * There is no MID knob — but there *is* a MIDDLE parameter on every amp
     * model. Treating it as absent would throw away usable advice; treating it
     * as a knob would send the player hunting for one that is not there.
     */
    @Test
    fun `middle exists but not on the panel`() {
        val middle = amp.control("Middle")
        assertNotNull(middle)
        assertEquals(Reach.DISPLAY, middle!!.reach)
        assertTrue("the note must say where to find it", middle.note.contains("Fender Tone"))
        assertTrue(amp.panelControls.none { it.name == "Middle" })
    }

    /**
     * Master Volume is loudness, not tone. A card that prescribes it is telling
     * the player how loud to be in their own house, and is wrong at every other
     * volume.
     */
    @Test
    fun `master volume is a level control, not a tone control`() {
        assertEquals(ControlRole.LEVEL, amp.control("Master Volume")!!.role)
        assertTrue(amp.toneControls.none { it.name == "Master Volume" })
        assertEquals(
            listOf("Gain", "Volume", "Treble", "Bass", "Middle"),
            amp.toneControls.map { it.name },
        )
    }

    @Test
    fun `twenty amp models, matching the manual`() {
        assertEquals(20, amp.ampModels.size)
        listOf("50s Twin", "80s Rock", "Twin Clean", "Metal 2000", "Princeton")
            .forEach { name ->
                assertNotNull(name, amp.ampModels.firstOrNull { it.name == name })
            }
        assertEquals("no duplicates", amp.ampModels.size, amp.ampModels.map { it.name }.distinct().size)
    }

    @Test
    fun `twenty-six effects across the four preset categories`() {
        assertEquals(26, amp.effects.size)
        assertEquals(11, amp.effectsIn(EffectCategory.STOMP).size)
        assertEquals(7, amp.effectsIn(EffectCategory.MOD).size)
        assertEquals(3, amp.effectsIn(EffectCategory.DELAY).size)
        assertEquals(5, amp.effectsIn(EffectCategory.REVERB).size)
    }

    /**
     * One slot per category. A card suggesting two overdrives describes
     * something the amp cannot be made to do.
     */
    @Test
    fun `a preset holds one effect of each category`() {
        assertEquals(1, amp.slotsPerCategory)
        assertEquals(60, amp.presetSlots)
        assertEquals(30, amp.factoryPresets)
    }

    /** Guards the lookup the prompt builder will use to reject invented names. */
    @Test
    fun `control lookup is case-insensitive and rejects what is not there`() {
        assertNotNull(amp.control("gAiN"))
        assertNull(amp.control("Presence"))
        assertNull(amp.control("Reverb"))
    }
}
