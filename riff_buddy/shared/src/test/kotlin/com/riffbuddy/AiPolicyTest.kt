package com.riffbuddy

import com.riffbuddy.ai.AiPolicy
import com.riffbuddy.ai.AiResult
import com.riffbuddy.ai.ProviderId
import com.riffbuddy.ai.ResponseParsing
import com.riffbuddy.ai.UsageToday
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPolicyTest {

    private val success = AiResult.Success("ok", null, ProviderId.GEMINI, "m", 10)
    private val malformed = AiResult.Malformed(ProviderId.GEMINI, "not json")
    private val limited = AiResult.RateLimited(ProviderId.GEMINI, 30)

    /** A malformed-JSON reply triggers exactly one retry, then a friendly error. */
    @Test
    fun `malformed retries once and only once`() {
        assertEquals(
            AiPolicy.Step.RetrySameProvider,
            AiPolicy.next(malformed, attemptsOnProvider = 1, fallbackAvailable = false),
        )
        assertEquals(
            AiPolicy.Step.GiveUp,
            AiPolicy.next(malformed, attemptsOnProvider = 2, fallbackAvailable = false),
        )
    }

    @Test
    fun `after its one retry a malformed reply tries the other provider`() {
        assertEquals(
            AiPolicy.Step.TryFallback,
            AiPolicy.next(malformed, attemptsOnProvider = 2, fallbackAvailable = true),
        )
    }

    /**
     * A rate limit is about the provider, not the prompt. Retrying it is
     * guaranteed to fail and spends time the UI is showing a spinner for.
     */
    @Test
    fun `a rate limit never retries the same provider`() {
        assertEquals(
            AiPolicy.Step.TryFallback,
            AiPolicy.next(limited, attemptsOnProvider = 1, fallbackAvailable = true),
        )
        assertEquals(
            AiPolicy.Step.GiveUp,
            AiPolicy.next(limited, attemptsOnProvider = 1, fallbackAvailable = false),
        )
    }

    @Test
    fun `success is accepted and offline gives up`() {
        assertEquals(AiPolicy.Step.Accept, AiPolicy.next(success, 1, true))
        assertEquals(AiPolicy.Step.GiveUp, AiPolicy.next(AiResult.Offline, 1, true))
        assertEquals(AiPolicy.Step.GiveUp, AiPolicy.next(AiResult.NoKey, 1, true))
    }

    /** Whatever happens, the policy must terminate. */
    @Test
    fun `no sequence of steps can loop forever`() {
        var attempts = 0
        var fallback = true
        var step: AiPolicy.Step
        var guard = 0
        do {
            attempts++
            step = AiPolicy.next(malformed, attempts, fallback)
            if (step == AiPolicy.Step.TryFallback) { fallback = false; attempts = 0 }
            guard++
        } while (step != AiPolicy.Step.GiveUp && guard < 20)
        assertTrue("policy should terminate, took $guard steps", guard < 20)
    }

    // ---- caching ----------------------------------------------------------

    @Test
    fun `the same request produces the same key`() {
        val a = AiPolicy.cacheKey("tone", "v1", "Nothing Else Matters", "rhythm", "rig-1")
        val b = AiPolicy.cacheKey("tone", "v1", "  nothing else matters ", "Rhythm", "rig-1")
        assertEquals(a, b)
    }

    /** Editing a prompt template must invalidate what it produced. */
    @Test
    fun `a new prompt version is a new key`() {
        assertNotEquals(
            AiPolicy.cacheKey("tone", "v1", "song"),
            AiPolicy.cacheKey("tone", "v2", "song"),
        )
    }

    @Test
    fun `a different rig is a different question`() {
        assertNotEquals(
            AiPolicy.cacheKey("tone", "v1", "song", "rhythm", "rig-1"),
            AiPolicy.cacheKey("tone", "v1", "song", "rhythm", "rig-2"),
        )
    }

    // ---- parsing -----------------------------------------------------------

    @Test
    fun `gemini and openai replies both yield their text`() {
        assertEquals(
            "hello",
            ResponseParsing.geminiText("""{"candidates":[{"content":{"parts":[{"text":"hello"}]}}]}"""),
        )
        assertEquals(
            "hello",
            ResponseParsing.openAiText("""{"choices":[{"message":{"content":"hello"}}]}"""),
        )
    }

    @Test
    fun `an empty or broken reply parses to null rather than throwing`() {
        listOf("", "not json", "{}", """{"candidates":[]}""").forEach {
            assertNull(it, ResponseParsing.geminiText(it))
        }
        listOf("", "{}", """{"choices":[]}""").forEach {
            assertNull(it, ResponseParsing.openAiText(it))
        }
    }

    /**
     * Models fence their JSON or chat before it even when told not to. Being
     * tolerant turns a retry into a success for free; being strict spends a
     * request punishing a cosmetic habit.
     */
    @Test
    fun `json is recovered from fences and chatter`() {
        val expected = """{"gain":6}"""
        listOf(
            expected,
            "```json\n$expected\n```",
            "```\n$expected\n```",
            "Here you go:\n$expected\nHope that helps!",
        ).forEach { raw ->
            val json = ResponseParsing.extractJson(raw)
            assertEquals(raw, 6, json?.optInt("gain"))
        }
        assertNull(ResponseParsing.extractJson("no object here"))
    }

    @Test
    fun `retry-after is read in seconds and in groq's duration form`() {
        assertEquals(30, ResponseParsing.retryAfterSeconds("30"))
        assertEquals(8, ResponseParsing.retryAfterSeconds("7.66s"))
        assertEquals(180, ResponseParsing.retryAfterSeconds("2m59.56s"))
        assertNull(ResponseParsing.retryAfterSeconds(null))
        assertNull(ResponseParsing.retryAfterSeconds("whenever"))
    }

    // ---- usage ------------------------------------------------------------

    @Test
    fun `usage warns near the limit without blocking`() {
        assertFalse(UsageToday(ProviderId.GROQ, 100, 1000).nearLimit)
        assertTrue(UsageToday(ProviderId.GROQ, 800, 1000).nearLimit)
        assertFalse(UsageToday(ProviderId.GROQ, 800, 1000).exhausted)
        assertTrue(UsageToday(ProviderId.GROQ, 1000, 1000).exhausted)
        assertEquals(0.5f, UsageToday(ProviderId.GEMINI, 100, 200).fraction, 0.001f)
    }

    // ---- repetition loops --------------------------------------------------

    /**
     * The reply that caused this, trimmed. Gemma answered "how to play Floods"
     * with a `-ness` loop that filled the whole output budget, and because it was
     * valid JSON with the one required field present it was accepted, cached, and
     * returned on every later ask.
     */
    private val floods = "A slow-tempered" + "-ness".repeat(210)

    @Test
    fun `a repetition loop is not an answer`() {
        assertTrue(AiPolicy.looksDegenerate(floods))
    }

    @Test
    fun `a loop is caught wherever it starts`() {
        val prose = "The intro is a clean arpeggio over a D minor shape, played with the " +
            "bridge pickup rolled back. Take it slowly at first and let each note ring. "
        assertTrue(AiPolicy.looksDegenerate(prose + "ness ".repeat(40)))
    }

    /**
     * The bar has to sit above what real answers do. These are the shapes that
     * would be embarrassing to reject: numbered steps, a repeated chord, a
     * technique named in every section, and a long run of ordinary prose.
     */
    @Test
    fun `real answers are not mistaken for loops`() {
        assertFalse(
            AiPolicy.looksDegenerate(
                "Palm-mute the verse. Palm-mute the pre-chorus too. The chorus opens up, " +
                    "but the second half is palm-muted again, and the outro is palm-muted " +
                    "all the way to the last chord. Palm muting is most of this song."
            )
        )
        assertFalse(
            AiPolicy.looksDegenerate(
                "Progression: Dm - Dm - Bb - C - Dm - Dm - Bb - C - Dm - Dm - Bb - C - " +
                    "Dm - Dm - Bb - C, four times through, then the solo section."
            )
        )
        assertFalse(
            AiPolicy.looksDegenerate(
                (1..12).joinToString(" ") { "$it. Work the riff at ${60 + it * 5} bpm until clean." }
            )
        )
        assertFalse(
            AiPolicy.looksDegenerate(
                "Floods is built on a slow, deliberate clean intro that gives way to a heavy " +
                    "riff in D standard. The solo at the end is the hardest part: it is mostly " +
                    "legato with a long tapped run, and it sits high on the neck."
            )
        )
    }

    /** Too short to judge — a terse answer must not be thrown away. */
    @Test
    fun `short replies are left alone`() {
        assertFalse(AiPolicy.looksDegenerate("Yes. Yes. Yes. Yes. Yes. Yes. Yes. Yes."))
        assertFalse(AiPolicy.looksDegenerate(""))
    }

    // ---- what happens before a request -------------------------------------

    @Test
    fun `a cached answer is served offline, which is the point of caching it`() {
        assertEquals(
            AiPolicy.Start.UseCache,
            AiPolicy.start(hasCache = true, refresh = false, hasAnyKey = true, online = false),
        )
        // No key either: still served. The answer is already on disk.
        assertEquals(
            AiPolicy.Start.UseCache,
            AiPolicy.start(hasCache = true, refresh = false, hasAnyKey = false, online = false),
        )
    }

    @Test
    fun `an identical request makes no network call`() {
        assertEquals(
            AiPolicy.Start.UseCache,
            AiPolicy.start(hasCache = true, refresh = false, hasAnyKey = true, online = true),
        )
    }

    /**
     * The trap this exists to stop. "Ask again" with no connection must not
     * delete the only answer there is and leave an error in its place.
     */
    @Test
    fun `asking again offline keeps the cached answer`() {
        assertEquals(
            AiPolicy.Start.Stop(AiResult.Offline),
            AiPolicy.start(hasCache = true, refresh = true, hasAnyKey = true, online = false),
        )
    }

    @Test
    fun `asking again with a connection drops the cached answer first`() {
        assertEquals(
            AiPolicy.Start.Ask(dropCache = true),
            AiPolicy.start(hasCache = true, refresh = true, hasAnyKey = true, online = true),
        )
    }

    @Test
    fun `no key is reported before no connection, because it is the fixable one`() {
        assertEquals(
            AiPolicy.Start.Stop(AiResult.NoKey),
            AiPolicy.start(hasCache = false, refresh = false, hasAnyKey = false, online = false),
        )
    }

    @Test
    fun `offline with nothing cached says so rather than trying`() {
        assertEquals(
            AiPolicy.Start.Stop(AiResult.Offline),
            AiPolicy.start(hasCache = false, refresh = false, hasAnyKey = true, online = false),
        )
    }

    @Test
    fun `a fresh question with a key and a connection just asks`() {
        assertEquals(
            AiPolicy.Start.Ask(dropCache = false),
            AiPolicy.start(hasCache = false, refresh = false, hasAnyKey = true, online = true),
        )
    }
}
