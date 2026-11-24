package com.riffbuddy.ai

import com.riffbuddy.data.AiCacheRepo
import com.riffbuddy.data.SecureStore
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * The one place the rest of the app asks the AI anything.
 *
 * Holds the parts that need a device — keys, the cache, the usage counters —
 * and defers every judgment to [AiPolicy], which is pure and tested. The
 * sequence it implements is: try the primary, retry once on a
 * malformed reply, fall back on a rate limit, and say which provider answered.
 */
class AiCoach(
    private val keys: SecureStore,
    private val cache: AiCacheRepo,
    private var configFor: (ProviderId) -> ProviderConfig = ProviderConfig::defaultFor,
    private var primary: () -> ProviderId = { ProviderId.GROQ },
    private val isOnline: () -> Boolean = { true },
) {

    /** Settings changes which model each provider uses; this is how it lands. */
    fun useConfig(supplier: (ProviderId) -> ProviderConfig) {
        configFor = supplier
    }

    /**
     * Settings also chooses which provider is tried first, and that has to land
     * here for the same reason [useConfig] does: without it the radio button
     * moves and every request still goes to Gemini.
     */
    fun usePrimary(supplier: () -> ProviderId) {
        primary = supplier
    }

    /**
     * Whether a paid provider may be asked when it is not the main one. Off
     * unless Settings turns it on (the `Repo.AI_PAID_FALLBACK` setting), so
     * a stored paid key spends nothing until the player says so.
     */
    fun usePaidFallback(supplier: () -> Boolean) {
        paidFallback = supplier
    }

    private var paidFallback: () -> Boolean = { false }

    /** Whether the device looks online now; cheap, so screens may poll it. */
    fun online(): Boolean = runCatching { isOnline() }.getOrDefault(true)

    /** Providers with a key stored, in Settings order; [ProviderId.paid] tells them apart. */
    fun keyedProviders(): List<ProviderId> = keys.providersWithKeys().let { keyed -> ProviderId.entries.filter { it in keyed } }

    /** Who a plain question would go to right now, in order: [AiPolicy.providerOrder] for the live settings. */
    fun answerOrder(): List<ProviderId> =
        AiPolicy.providerOrder(primary(), keys.providersWithKeys(), paidFallback())

    private fun providerFor(id: ProviderId): AiProvider {
        val config = configFor(id)
        return when (id) {
            ProviderId.GEMINI -> GeminiProvider(config)
            ProviderId.ANTHROPIC -> AnthropicProvider(config)
            ProviderId.GROQ, ProviderId.MISTRAL, ProviderId.OPENROUTER,
            ProviderId.OPENAI, ProviderId.DEEPSEEK -> OpenAiCompatProvider(config)
        }
    }

    /**
     * Runs a request through the whole policy.
     *
     * [cacheKey] null means do not cache — refinements and chat turns are new
     * questions every time, so caching them would serve a stale answer to a
     * different question.
     *
     * [refresh] is Regenerate: throw the cached answer away and really
     * ask. The row is dropped before the call rather than after, so a failed
     * retry cannot leave the bad answer in place to be served again — but only
     * once [AiPolicy.start] has established that a call is going to happen.
     *
     * [onPartial] asks for the answer as it is written. It is offered
     * per call rather than per provider because whether streaming helps is a
     * property of the *screen*: reading a chat answer as it lands is the point,
     * and watching a tone card's JSON assemble itself brace by brace is not.
     * A cached answer never streams — there is nothing to wait for — so the
     * callback fires once with the whole thing, and every caller that shows
     * partial text gets the same shape of update either way.
     */
    suspend fun ask(
        request: AiRequest,
        cacheKey: String? = null,
        refresh: Boolean = false,
        onPartial: ((String) -> Unit)? = null,
    ): AiResult {
        val cached = cacheKey?.let { cache.get(it) }
        val available = keys.providersWithKeys()

        when (
            val start = AiPolicy.start(
                hasCache = cached != null,
                refresh = refresh,
                hasAnyKey = available.isNotEmpty(),
                online = isOnline(),
            )
        ) {
            AiPolicy.Start.UseCache -> return cached!!.copy(fromCache = true)
                .also { onPartial?.invoke(it.text) }
            is AiPolicy.Start.Stop -> return start.result
            is AiPolicy.Start.Ask -> if (start.dropCache && cacheKey != null) {
                cache.invalidate(cacheKey)
            }
        }

        // The preferred provider, then every other free one with a key, in
        // order: with four free tiers, a busy one shouldn't end the request
        // when a third or fourth could answer it. Paid ones join only as
        // AiPolicy.providerOrder allows.
        val allowed = AiPolicy.providerOrder(primary(), available, paidFallback())
        if (allowed.isEmpty()) {
            return AiResult.Failed(
                null,
                "Your only AI keys are for paid providers. Choose one to use first in Me › Settings, " +
                    "or turn on Use paid providers as a fallback.",
            )
        }
        // A web search can only go to a provider whose model can search.
        val able = if (request.webSearch) allowed.filter { WebSearch.supports(it, configFor(it).model) } else allowed
        if (able.isEmpty()) {
            return AiResult.Failed(null, "Searching the web needs a Gemini key: add a free one in Me › Settings.")
        }
        val first = if (request.webSearch) WebSearch.PREFERENCE.firstOrNull { it in able } ?: able.first() else able.first()
        val order = listOf(first) + able.filter { it != first }
        var next = 1

        var current = first
        var fallbackLeft = order.size > 1
        var attempts = 0
        var last: AiResult = AiResult.Failed(null, "Nothing was attempted")

        while (true) {
            val key = keys.get(current)
            if (key == null) {
                last = AiResult.NoKey
                break
            }
            attempts++
            last = runAttempt(current, request, key, onPartial)

            when (AiPolicy.next(last, attempts, fallbackLeft)) {
                AiPolicy.Step.Accept -> break
                AiPolicy.Step.RetrySameProvider -> Unit
                AiPolicy.Step.TryFallback -> {
                    current = order.getOrNull(next++) ?: break
                    fallbackLeft = next < order.size
                    attempts = 0
                }
                AiPolicy.Step.GiveUp -> break
            }
        }

        if (last is AiResult.Success && cacheKey != null) cache.put(cacheKey, last)
        return last
    }

    private suspend fun runAttempt(
        id: ProviderId,
        request: AiRequest,
        key: String,
        onPartial: ((String) -> Unit)? = null,
    ): AiResult =
        try {
            // Counted before the call, not after: a request that times out still
            // consumed free-tier quota, and a counter that only records successes
            // would quietly under-report exactly when the limit is being hit.
            cache.recordUsage(id)
            val provider = providerFor(id)
            // Structured output is never streamed: the fragments are unusable
            // until the closing brace, and the schema is validated whole.
            // A web search isn't streamed either: its sources arrive only with
            // the finished reply, and the wait is mostly the search itself.
            if (onPartial != null && request.schema == null && !request.webSearch) {
                // No overall deadline here on purpose. An answer that is still
                // arriving is not late; the streaming client's read timeout is
                // what catches a provider that has gone quiet.
                provider.stream(request, key, onPartial)
            } else {
                withTimeout(request.timeoutMs) { provider.generate(request, key) }
            }
        } catch (e: TimeoutCancellationException) {
            AiResult.Failed(id, "Timed out after ${request.timeoutMs / 1000}s")
        }

    suspend fun usage(): List<UsageToday> = ProviderId.entries.map { id ->
        UsageToday(id, cache.usageToday(id), configFor(id).dailyRequestBudget)
    }

    /** What one press of Test connection established. */
    data class KeyTest(
        val result: AiResult,
        /** The reply was JSON of the shape that was asked for. */
        val honorsSchema: Boolean,
    ) {
        val reachable: Boolean get() = result is AiResult.Success || result is AiResult.RateLimited
    }

    /**
     * The Test connection button.
     *
     * Sends the schema probe rather than a plain hello, because a bare "are you
     * there?" only proves the key is valid — and the question that actually
     * decides whether a model can produce a tone card is whether it honors a
     * schema. Neither provider documents that per model in a form worth
     * trusting, so one request answers both: a reply of the right shape means
     * reachable *and* usable, and a reply of the wrong shape means the model
     * works but its output will need checking on this end.
     */
    suspend fun testKey(id: ProviderId): KeyTest {
        val key = keys.get(id) ?: return KeyTest(AiResult.NoKey, honorsSchema = false)
        val result = runAttempt(id, ModelCatalog.probeSchema(), key)
        return KeyTest(result, honorsSchema = ModelCatalog.probePassed(result))
    }
}
