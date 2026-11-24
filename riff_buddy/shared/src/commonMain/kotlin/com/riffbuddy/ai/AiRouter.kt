package com.riffbuddy.ai

import okio.ByteString.Companion.encodeUtf8

/**
 * Decides what to do with a provider's answer.
 *
 * Pure, so the rules that matter — retry exactly once, fall back on a rate
 * limit, never loop — can be tested without a network or a key. The HTTP lives
 * in the providers; the judgment lives here.
 */
object AiPolicy {

    /** What the router should do next. */
    sealed interface Step {
        data object Accept : Step
        /** Same provider, one more time — exactly once, never a loop. */
        data object RetrySameProvider : Step
        data object TryFallback : Step
        data object GiveUp : Step
    }

    /**
     * [attemptsOnProvider] counts attempts already made against the provider
     * that produced [result]; [fallbackAvailable] is false once it has been
     * tried, or when there is no second key.
     */
    fun next(result: AiResult, attemptsOnProvider: Int, fallbackAvailable: Boolean): Step = when (result) {
        is AiResult.Success -> Step.Accept

        // A malformed reply is usually a one-off: the same prompt often works
        // the second time. Once. Retrying twice spends a free-tier request to
        // make the same mistake again.
        is AiResult.Malformed ->
            if (attemptsOnProvider < 2) Step.RetrySameProvider
            else if (fallbackAvailable) Step.TryFallback
            else Step.GiveUp

        // Rate limited means this provider is done for now, whatever the
        // prompt was. Retrying it is guaranteed to fail; the other one is the
        // only thing that can help.
        is AiResult.RateLimited -> if (fallbackAvailable) Step.TryFallback else Step.GiveUp

        is AiResult.Failed -> if (fallbackAvailable) Step.TryFallback else Step.GiveUp

        // Offline and NoKey are conditions, not failures of a provider —
        // there is nothing a second provider can do about either.
        AiResult.Offline, AiResult.NoKey -> Step.GiveUp
    }

    /**
     * Who is asked, in order: the main provider first, free or paid, then
     * every other free provider with a key, then, only when the player has
     * turned paid fallback on, every other paid provider with a key.
     *
     * The rule that matters is the last part. A paid key is the player's
     * money, so storing one must never make the coach spend it behind their
     * back: a paid provider answers only because it was chosen as the main
     * one, or because paid fallback was explicitly switched on. Everything
     * that asks the AI (Ask, tone cards, song assists, gear suggestions)
     * goes through [AiCoach.ask], which uses this order.
     *
     * Empty when no provider may be asked: no keys at all, or only paid keys
     * with none of them chosen.
     */
    fun providerOrder(primary: ProviderId, keyed: Set<ProviderId>, paidFallback: Boolean): List<ProviderId> {
        val first = listOfNotNull(primary.takeIf { it in keyed })
        val free = ProviderId.FREE.filter { it in keyed && it != primary }
        val paid = if (paidFallback) ProviderId.PAID.filter { it in keyed && it != primary } else emptyList()
        return first + free + paid
    }

    /**
     * The main provider to assume when none has been chosen: the first free
     * one with a key. Never a paid one, which has to be picked on purpose.
     */
    fun defaultPrimary(keyed: Set<ProviderId>): ProviderId =
        ProviderId.FREE.firstOrNull { it in keyed } ?: ProviderId.GROQ

    /** What happens before any request goes out. */
    sealed interface Start {
        /** Serve the cached answer. Nothing is asked. */
        data object UseCache : Start
        /** Nothing can be asked at all, and the cache must be left alone. */
        data class Stop(val result: AiResult) : Start
        /** Go ahead. [dropCache] means a refresh was asked for. */
        data class Ask(val dropCache: Boolean) : Start
    }

    /**
     * The order these four checks happen in is the whole of this function, and
     * two of them are easy to get wrong.
     *
     * A refresh must not drop the cached answer until a request is actually
     * going to be made. "Ask again" with no connection would otherwise delete
     * the only answer there is and replace it with "no connection" — the worst
     * possible trade for pressing a button.
     *
     * And the cache is read before the key and connection checks, not after,
     * because an identical request should make no network call: an answer
     * already on disk is an answer whether or not there is a signal, which is
     * how a cached card can still show offline.
     */
    fun start(hasCache: Boolean, refresh: Boolean, hasAnyKey: Boolean, online: Boolean): Start = when {
        hasCache && !refresh -> Start.UseCache
        !hasAnyKey -> Start.Stop(AiResult.NoKey)
        !online -> Start.Stop(AiResult.Offline)
        else -> Start.Ask(dropCache = refresh)
    }

    /**
     * Whether a reply is a repetition loop rather than an answer.
     *
     * Small open models fall into one, and the one that prompted this came back
     * for Pantera's "Floods" as
     * `"A slow-tempered-ness-ness-ness-ness-…"` for four hundred more
     * `-ness`, cut off mid-token when it hit the output limit. It was valid JSON
     * with the one required field present, so every check in the pipeline passed
     * it, it was cached, and re-asking returned it forever.
     *
     * The test is a run of one short chunk repeated far past anything language
     * does. Real text repeats — a chord name, a section heading, "palm muting"
     * three times in a paragraph — so the bar is deliberately high: a dozen
     * consecutive copies of the same thing, back to back, with no other content
     * between them. Prose does not do that; a decoder stuck in a loop does
     * nothing else.
     */
    fun looksDegenerate(text: String): Boolean {
        val squashed = text.replace(Regex("""\s+"""), " ").trim()
        if (squashed.length < MIN_LENGTH_TO_JUDGE) return false

        // Chunks up to 24 characters cover the observed cases (a syllable, a
        // word, a short phrase) without the cost of testing every length.
        for (size in 1..24) {
            var index = 0
            while (index + size * MIN_REPEATS <= squashed.length) {
                val chunk = squashed.substring(index, index + size)
                var repeats = 1
                var at = index + size
                while (at + size <= squashed.length && squashed.regionMatches(at, chunk, 0, size)) {
                    repeats++
                    at += size
                }
                if (repeats >= MIN_REPEATS) return true
                // A run of n copies starting here means no longer run starts
                // inside it, so skip past what was just measured.
                index = if (repeats > 1) at - size else index + 1
            }
        }
        return false
    }

    /** Below this there is not enough text for a loop to be distinguishable. */
    private const val MIN_LENGTH_TO_JUDGE = 120

    /**
     * Twelve back-to-back copies. "ha ha ha ha" is four and legitimate; twelve
     * identical chunks in a row with nothing between them is a stuck decoder.
     */
    private const val MIN_REPEATS = 12

    /**
     * An identical request must make no network call.
     *
     * The prompt version is in the key on purpose, so editing a prompt template
     * invalidates everything it produced without anyone having to remember to
     * clear a cache. The rig and the song are in it because the same song on a
     * different rig is a different question.
     */
    fun cacheKey(feature: String, promptVersion: String, vararg parts: String?): String {
        val material = (listOf(feature, promptVersion) + parts.map { it.orEmpty() })
            .joinToString("\u0000") { it.trim().lowercase() }
        return material.encodeUtf8().sha256().hex().take(32)
    }
}

/**
 * Free-tier usage, counted locally.
 *
 * Counting requests rather than tokens because that is the limit a player will
 * actually hit, and because a count is something the app can be sure of without
 * trusting a provider's headers — Gemini sends none at all.
 */
data class UsageToday(val provider: ProviderId, val requests: Int, val budget: Int) {
    val fraction: Float get() = if (budget <= 0) 0f else (requests.toFloat() / budget).coerceIn(0f, 1f)

    /** Worth warning about, but well short of stopping anything. */
    val nearLimit: Boolean get() = budget > 0 && requests >= budget * 0.8

    val exhausted: Boolean get() = budget > 0 && requests >= budget
}
