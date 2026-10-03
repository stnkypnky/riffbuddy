package com.riffbuddy.desktop

import com.riffbuddy.desktop.DesktopAudioInputs.Companion.isDefaultAlias
import com.riffbuddy.desktop.DesktopAudioInputs.Companion.readableName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each system names its inputs differently; the list has to read right on all of them. */
class DesktopInputNamesTest {

    @Test
    fun linuxUsesTheReadableDescription() {
        assertEquals(
            "HDA Intel PCH · ALC3253 Analog",
            readableName("PCH [plughw:0,0]", "Direct Audio Device: HDA Intel PCH, ALC3253 Analog, ALC3253 Analog"),
        )
    }

    @Test
    fun windowsKeepsTheDeviceName() {
        // Every Windows input shares this description; labeling by it made six identical rows.
        val description = "Direct Audio Device: DirectSound Capture"
        assertEquals("Microphone (Elgato Wave:3)", readableName("Microphone (Elgato Wave:3)", description))
        assertEquals("Wave Link Stream (Elgato Wave:3)", readableName("Wave Link Stream (Elgato Wave:3)", description))
    }

    @Test
    fun macKeepsTheDeviceName() {
        assertEquals("MacBook Pro Microphone", readableName("MacBook Pro Microphone", "Direct Audio Device: MacBook Pro Microphone"))
    }

    @Test
    fun theDefaultIsNotListedTwice() {
        assertTrue(isDefaultAlias("default [default]"))
        assertTrue(isDefaultAlias("Primary Sound Capture Driver"))
        assertFalse(isDefaultAlias("Microphone (Elgato Wave:3)"))
    }
}
