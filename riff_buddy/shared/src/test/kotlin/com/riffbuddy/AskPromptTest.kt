package com.riffbuddy

import com.riffbuddy.ai.prompts.AskPrompt
import com.riffbuddy.domain.Song
import com.riffbuddy.domain.gear.EspLtdEc256
import com.riffbuddy.domain.gear.MustangLt25
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Toggling context changes the payload, verified by a payload snapshot. The
 * point is that the privacy promise is checkable — a
 * toggle that does not actually change what is sent would be a lie told in a
 * checkbox.
 */
class AskPromptTest {

    private val amp = MustangLt25.profile
    private val guitar = EspLtdEc256.profile
    private val song = Song(id = 1, title = "Nothing Else Matters", artist = "Metallica")

    private fun build(context: AskPrompt.Context) =
        AskPrompt.system(context, amp, guitar, song, "intermediate", "rock, blues")

    @Test
    fun `rig off means no gear in the payload`() {
        val without = build(AskPrompt.Context(includeRig = false))
        assertFalse(without.contains("Mustang"))
        assertFalse(without.contains("EC-256"))
        assertFalse(without.contains("80s Rock"))

        val with = build(AskPrompt.Context(includeRig = true))
        assertTrue(with.contains("Mustang LT25"))
        assertTrue(with.contains("EC-256"))
    }

    @Test
    fun `song off means no song in the payload`() {
        assertFalse(build(AskPrompt.Context(includeSong = false)).contains("Nothing Else Matters"))
        assertTrue(build(AskPrompt.Context(includeSong = true)).contains("Nothing Else Matters"))
    }

    @Test
    fun `level off means no level in the payload`() {
        assertFalse(build(AskPrompt.Context(includeLevel = false)).contains("intermediate"))
        assertTrue(build(AskPrompt.Context(includeLevel = true)).contains("intermediate"))
    }

    /** Everything off should send almost nothing but the instruction itself. */
    @Test
    fun `all toggles off sends only the ground rules`() {
        val bare = build(AskPrompt.Context(includeRig = false, includeSong = false, includeLevel = false))
        assertTrue(bare.contains("guitar teacher"))
        assertFalse(bare.contains("Mustang"))
        assertFalse(bare.contains("Metallica"))
        assertFalse(bare.contains("intermediate"))
    }

    /** The lyric and tab rule is not optional and must survive every combination. */
    @Test
    fun `the no-lyrics rule is always present`() {
        listOf(
            AskPrompt.Context(false, false, false),
            AskPrompt.Context(true, true, true),
        ).forEach { assertTrue(build(it).contains("Never reproduce lyrics")) }
    }

    /** The preview must be the payload, not a description of it. */
    @Test
    fun `the preview contains the real system prompt and question`() {
        val system = build(AskPrompt.Context(includeRig = true))
        val preview = AskPrompt.preview(system, "What is a good warm-up?")
        assertTrue(preview.contains(system))
        assertTrue(preview.contains("What is a good warm-up?"))
    }

    /** With no gear saved, the rig section is simply absent rather than empty. */
    @Test
    fun `a missing rig does not leave a hollow section`() {
        val prompt = AskPrompt.system(
            AskPrompt.Context(includeRig = true), null, null, null, "beginner", "blues",
        )
        assertFalse(prompt.contains("Their rig"))
    }
}
