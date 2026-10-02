package com.riffbuddy

import com.riffbuddy.domain.PlayerProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerProfileTest {
    @Test
    fun `the profile round-trips through settings`() {
        val p = PlayerProfile(PlayerProfile.Level.BEGINNER, listOf("metal", "rock"), listOf("learn songs"))
        val stored = p.toStored()
        assertEquals(p, PlayerProfile.fromStored { stored[it] })
    }

    @Test
    fun `nothing stored assumes no style`() {
        // A new player hasn't said what they play; the prompt shouldn't
        // decide for them (it used to say "rock, blues", one player's taste).
        val p = PlayerProfile.fromStored { null }
        assertEquals("intermediate", p.level.forPrompt)
        assertEquals("not specified", p.stylesForPrompt)
    }

    @Test
    fun `a beginner's prompt says tab, not notation`() {
        assertTrue(PlayerProfile.Level.BEGINNER.forPrompt.contains("tab"))
    }
}
