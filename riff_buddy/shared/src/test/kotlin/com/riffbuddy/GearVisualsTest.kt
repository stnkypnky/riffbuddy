package com.riffbuddy

import com.riffbuddy.data.Rig
import com.riffbuddy.data.SavedAmp
import com.riffbuddy.data.SavedGuitar
import com.riffbuddy.data.SavedPedal
import com.riffbuddy.domain.gear.AmpKind
import com.riffbuddy.domain.gear.AmpProfile
import com.riffbuddy.domain.gear.GuitarProfile
import com.riffbuddy.domain.gear.PedalProfile
import com.riffbuddy.ui.gear.ChainKind
import com.riffbuddy.ui.gear.chainNodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A rig's signal chain as the Gear screen draws it. */
class GearVisualsTest {

    private val guitar = SavedGuitar(1, "EC-256", GuitarProfile("ESP LTD", "EC-256"))
    private val amp = SavedAmp(2, "LT25", AmpProfile("Fender", "Mustang LT25", AmpKind.MODELING, emptyList()))
    private fun pedal(id: Long) = SavedPedal(id, "Pedal $id", PedalProfile("", ""))

    @Test
    fun `the signal runs guitar, pedals in order, amp`() {
        val rig = Rig(9, "Home", guitar, amp, true, listOf(pedal(10), pedal(11)))
        val nodes = chainNodes(rig)
        assertEquals(listOf(ChainKind.GUITAR, ChainKind.PEDAL, ChainKind.PEDAL, ChainKind.AMP), nodes.map { it.kind })
        assertEquals(listOf(1L, 10L, 11L, 2L), nodes.map { it.id })
    }

    @Test
    fun `pedals past the limit fold into one box that counts them`() {
        val rig = Rig(9, "Board", guitar, amp, true, (10L..14L).map(::pedal))
        val two = chainNodes(rig, maxPedals = 2)
        assertEquals(listOf("EC-256", "Pedal 10", "Pedal 11", "+3", "LT25"), two.map { it.name })
        assertEquals(ChainKind.MORE, two[3].kind)
        val none = chainNodes(rig, maxPedals = 0)
        assertEquals(listOf("EC-256", "+5", "LT25"), none.map { it.name })
        // Nothing to fold, nothing added.
        assertEquals(4, chainNodes(Rig(9, "", guitar, amp, true, listOf(pedal(10), pedal(11))), maxPedals = 2).size)
    }

    @Test
    fun `a missing guitar or amp still shows, marked as missing`() {
        val nodes = chainNodes(Rig(9, "Half", guitar, null, false))
        assertEquals("No amp", nodes.last().name)
        assertTrue(nodes.last().missing)
        assertEquals(null, nodes.last().id)
        assertTrue(!nodes.first().missing)
    }

    @Test
    fun `an empty rig is two placeholders, not nothing`() {
        val nodes = chainNodes(Rig(9, "Empty", null, null, false))
        assertEquals(listOf(ChainKind.GUITAR, ChainKind.AMP), nodes.map { it.kind })
        assertTrue(nodes.all { it.missing })
    }
}
