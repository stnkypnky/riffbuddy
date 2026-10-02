package com.riffbuddy.ai

import org.json.JSONObject

/**
 * The provider-agnostic AI layer.
 *
 * Several providers, one interface, and every detail that a vendor might
 * change next month held as configuration rather than baked in — endpoints,
 * model names, free-tier limits, even the field names for structured output.
 * Google's own documentation has given two different answers about the
 * current request shape on two different pages, which is the argument for
 * keeping all of it adjustable.
 */

/**
 * The free AI providers, in the order Settings lists them and the coach falls
 * back through them.
 *
 * Each is free with no card. Verified 2026-09-26 against each provider's own
 * docs or console; free tiers change, which is why the model list is always
 * fetched live (ModelCatalog) and the budgets are warnings, never limits.
 *
 * Cerebras (a one-time credit that needs a card) and GitHub Models (8K-token
 * requests) were looked at and left out.
 */
enum class ProviderId(
    val label: String,
    /** Where to get a key; Settings opens it. */
    val keyUrl: String,
    /** How to get the key once there, in a sentence or two. */
    val keySteps: String,
    /** What the free tier is, plainly. */
    val freeTier: String,
    /** The model to suggest: the smartest one the free tier serves well. */
    val recommendedModel: String,
    val recommendedWhy: String,
) {
    GROQ(
        "Groq", "https://console.groq.com/keys",
        "Sign in with Google or email, open API Keys, tap Create API Key, and copy it.",
        "Free, no card. About 1,000 requests a day. Very fast.",
        "openai/gpt-oss-120b", "The smartest free model here, and it fills in tone cards exactly",
    ),
    GEMINI(
        "Gemini", "https://aistudio.google.com/apikey",
        "Sign in with a Google account, tap Create API key, and copy it.",
        "Free, no card. About 200 requests a day on the default model.",
        "gemini-flash-latest", "Always Google's current Flash model, so it never goes out of date; can search the web",
    ),
    MISTRAL(
        "Mistral", "https://console.mistral.ai/api-keys",
        "Create an account, choose the free Experiment plan (it asks to verify a phone number), " +
            "then API Keys › Create new key, and copy it.",
        "Free, no card, phone number needed. On the free plan Mistral may use requests to train its models.",
        "mistral-large-latest", "Mistral's largest model, free on the Experiment plan",
    ),
    OPENROUTER(
        "OpenRouter", "https://openrouter.ai/settings/keys",
        "Sign in, open Keys, tap Create Key, and copy it. No credits needed for free models.",
        "Free, no card. 50 requests a day across its free models.",
        "openrouter/free", "Picks whichever free model can answer, so it keeps working as free models change",
    );

    companion object {
        fun fromName(raw: String?): ProviderId =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: GROQ
    }
}

/**
 * Everything about a provider that is not code.
 *
 * Defaults are what was verified against the docs on 2026-09-20; Settings can
 * override all of it without a rebuild, which is the point.
 */
data class ProviderConfig(
    val id: ProviderId,
    val baseUrl: String,
    val model: String,
    /** Requests per day on the free tier, as a warning threshold only. */
    val dailyRequestBudget: Int,
    val supportsJsonSchema: Boolean = true,
) {
    companion object {
        /**
         * `generateContent` with `generationConfig.response_schema`, verified
         * against ai.google.dev/api/generate-content.
         */
        val GEMINI_DEFAULT = ProviderConfig(
            id = ProviderId.GEMINI,
            baseUrl = "https://generativelanguage.googleapis.com/v1beta",
            // Google's alias for its current Flash model. gemini-2.5-flash, the
            // default until now, was closed to new users in September 2026;
            // an alias can't go stale like that.
            model = "gemini-flash-latest",
            dailyRequestBudget = 200,
        )

        /**
         * OpenAI-compatible. Groq only honors a full JSON *schema* on some
         * models; the rest accept JSON *mode*, which guarantees valid JSON but
         * not the right shape — so client-side validation is not optional
         * either way.
         */
        val GROQ_DEFAULT = ProviderConfig(
            id = ProviderId.GROQ,
            baseUrl = "https://api.groq.com/openai/v1",
            // Llama 3.3 70B, the default until now, left Groq's free tier on
            // 2026-08-16. gpt-oss-120b replaced it there, and honors a schema.
            model = "openai/gpt-oss-120b",
            dailyRequestBudget = 1_000,
        )

        /** OpenAI-compatible; every chat model takes a JSON schema. */
        val MISTRAL_DEFAULT = ProviderConfig(
            id = ProviderId.MISTRAL,
            baseUrl = "https://api.mistral.ai/v1",
            model = "mistral-large-latest",
            // Mistral doesn't publish its free numbers; this is only where
            // the usage bar starts to warn.
            dailyRequestBudget = 500,
        )

        /**
         * OpenAI-compatible. `openrouter/free` routes to a free model that
         * supports what the request needs, structured output included.
         */
        val OPENROUTER_DEFAULT = ProviderConfig(
            id = ProviderId.OPENROUTER,
            baseUrl = "https://openrouter.ai/api/v1",
            model = "openrouter/free",
            dailyRequestBudget = 50,
        )

        fun defaultFor(id: ProviderId) = when (id) {
            ProviderId.GEMINI -> GEMINI_DEFAULT
            ProviderId.GROQ -> GROQ_DEFAULT
            ProviderId.MISTRAL -> MISTRAL_DEFAULT
            ProviderId.OPENROUTER -> OPENROUTER_DEFAULT
        }

        /**
         * Whether [model] may be sent a JSON schema, or must ask for JSON mode.
         * Groq documents json_schema for the gpt-oss family only; sending it to
         * another Groq model is rejected outright.
         */
        fun schemaFor(id: ProviderId, model: String): Boolean = when (id) {
            ProviderId.GROQ -> model.contains("gpt-oss")
            ProviderId.GEMINI, ProviderId.MISTRAL, ProviderId.OPENROUTER -> true
        }

        /** The saved choice for [id], or its default. */
        fun forModel(id: ProviderId, model: String?): ProviderConfig =
            defaultFor(id).let { it.copy(model = model ?: it.model, supportsJsonSchema = schemaFor(id, model ?: it.model)) }
    }
}

/** What the caller asked for. */
data class AiRequest(
    val systemPrompt: String,
    val userPrompt: String,
    /** When set, the reply must be JSON matching this schema. */
    val schema: JSONObject? = null,
    val promptVersion: String = "v1",
    /** Names the feature for usage counting and caching. */
    val feature: String = "general",
    val timeoutMs: Long = 30_000,
    /** Earlier turns of the conversation, oldest first, sent ahead of [userPrompt]. */
    val history: List<ChatTurn> = emptyList(),
    /**
     * Let the model search the web. Only honored by a provider and model that
     * can ([WebSearch.supports]); never with a [schema], which search can't
     * be combined with.
     */
    val webSearch: Boolean = false,
)

/** One earlier message in a conversation. */
data class ChatTurn(val fromPlayer: Boolean, val text: String)

/** A page a web-searching answer drew on. */
data class WebSource(val title: String, val url: String)

/**
 * What came back.
 *
 * Deliberately a sealed result rather than exceptions: every one of these is an
 * ordinary thing that happens on a free tier, and the UI has a different,
 * specific thing to say about each. "Both are busy — try again in
 * 45 s. Everything else still works" is only possible if the layer distinguishes
 * a rate limit from a network drop.
 */
sealed interface AiResult {
    data class Success(
        val text: String,
        val json: JSONObject?,
        val provider: ProviderId,
        val model: String,
        val elapsedMs: Long,
        val fromCache: Boolean = false,
        /** Pages the answer drew on, when it searched the web. */
        val sources: List<WebSource> = emptyList(),
        /** What was searched for (Google's grounding reports it, and asks that it be shown). */
        val searchQueries: List<String> = emptyList(),
    ) : AiResult

    /** HTTP 429. [retryAfterSeconds] is null when the provider did not say. */
    data class RateLimited(val provider: ProviderId, val retryAfterSeconds: Int?) : AiResult

    /** Valid HTTP, unusable body — retried exactly once, then given up on. */
    data class Malformed(val provider: ProviderId, val detail: String) : AiResult

    data class Failed(val provider: ProviderId?, val message: String) : AiResult

    /** No key stored, so the UI shows guided setup rather than an error. */
    data object NoKey : AiResult

    data object Offline : AiResult
}

/** One provider's adapter. */
interface AiProvider {
    val config: ProviderConfig
    suspend fun generate(request: AiRequest, apiKey: String): AiResult

    /**
     * The same request, with the answer delivered as it is written.
     *
     * [onPartial] receives the text *so far*, not the newest fragment, so a
     * caller only has to assign it somewhere; and an attempt that starts over
     * begins by sending "", which clears whatever the last one had shown.
     *
     * The default is no streaming at all: one call, one callback with the whole
     * answer. Streaming is a nicety where a provider supports it, and a
     * provider that does not should cost the UI nothing to use.
     */
    suspend fun stream(request: AiRequest, apiKey: String, onPartial: (String) -> Unit): AiResult =
        generate(request, apiKey).also { if (it is AiResult.Success) onPartial(it.text) }
}

/**
 * Server-sent events, which is how the providers stream.
 *
 * Kept away from the HTTP for the usual reason: a stream is the hardest thing
 * here to reproduce on demand and the easiest to test from captured lines.
 */
object StreamParsing {

    /**
     * The payload of one `data:` line, or null for everything else.
     *
     * An SSE stream carries blank lines as keep-alives, `event:` and `id:`
     * lines, and comments starting with a colon. None of them are an error and
     * none of them are content.
     */
    fun sseData(line: String): String? {
        if (!line.startsWith("data:")) return null
        return line.removePrefix("data:").trim().ifBlank { null }
    }

    /** OpenAI's end-of-stream sentinel. Gemini just closes the connection. */
    fun isDone(payload: String): Boolean = payload == "[DONE]"

    /**
     * Gemini sends a whole response object per event, so the text it adds is
     * read exactly like a non-streamed reply.
     */
    fun geminiDelta(payload: String): String? = ResponseParsing.geminiText(payload)

    /** OpenAI-compatible: choices[0].delta.content, which may be absent. */
    fun openAiDelta(payload: String): String? {
        val root = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        val choices = root.optJSONArray("choices") ?: return null
        if (choices.length() == 0) return null
        return choices.optJSONObject(0)
            ?.optJSONObject("delta")
            ?.optString("content")
            ?.takeIf { it.isNotEmpty() }
    }
}

/**
 * Turning a provider's reply into text, kept apart from the HTTP so it can be
 * tested against captured bodies, malformed ones included.
 */
object ResponseParsing {

    /**
     * Gemini: candidates[0].content.parts[*].text, concatenated — except the
     * parts marked `"thought": true`.
     *
     * Thinking models (Gemma 4 on this API, for one) send their reasoning as
     * its own part ahead of the answer: "User wants five tips… Persona: guitar
     * teacher… Constraint: no tab", a full draft, a self-check, and only then
     * the reply. Concatenating every part would show the player all of it, and
     * in a tone card the draft's own braces can sit ahead of the real JSON,
     * which is how a good answer becomes "Reply was not JSON".
     */
    fun geminiText(body: String): String? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val candidates = root.optJSONArray("candidates") ?: return null
        if (candidates.length() == 0) return null
        val parts = candidates.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?: return null
        val text = buildString {
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                if (part.optBoolean("thought")) continue
                append(part.optString("text"))
            }
        }
        return text.ifBlank { null }
    }

    /** OpenAI-compatible: choices[0].message.content. */
    fun openAiText(body: String): String? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val choices = root.optJSONArray("choices") ?: return null
        if (choices.length() == 0) return null
        return choices.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * Pulls an object out of a reply that should have been pure JSON.
     *
     * Models wrap JSON in ```json fences, or add a sentence before it, even when
     * told not to. Being tolerant here turns a retry into a success at no cost;
     * being strict would spend a second request to punish a cosmetic habit.
     */
    fun extractJson(text: String): JSONObject? {
        val trimmed = text.trim()
        runCatching { return JSONObject(trimmed) }

        val fenced = Regex("```(?:json)?\\s*(\\{.*?})\\s*```", RegexOption.DOT_MATCHES_ALL)
            .find(trimmed)?.groupValues?.get(1)
        if (fenced != null) runCatching { return JSONObject(fenced) }

        val first = trimmed.indexOf('{')
        val last = trimmed.lastIndexOf('}')
        if (first in 0 until last) {
            runCatching { return JSONObject(trimmed.substring(first, last + 1)) }
        }
        return null
    }

    /** Seconds from a Retry-After header, which may be seconds or an HTTP date. */
    fun retryAfterSeconds(header: String?): Int? {
        val raw = header?.trim() ?: return null
        raw.toIntOrNull()?.let { return it }
        // Groq also uses forms like "7.66s" and "2m59.56s".
        val minutes = Regex("([0-9.]+)m").find(raw)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        val seconds = Regex("([0-9.]+)s").find(raw)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        val total = minutes * 60 + seconds
        return if (total > 0) kotlin.math.ceil(total).toInt() else null
    }
}

/**
 * Which models can search the web: Gemini only, through Google Search
 * grounding (`google_search`), about 500 a day free.
 *
 * Groq was tried and taken out (2026-09-27). Its gpt-oss models
 * have a built-in `browser_search` tool, but one search read whole pages into
 * the prompt: 53,000 to 158,000 tokens of a 200,000 daily allowance, which a
 * few searches exhaust, leaving tone cards nothing for the rest of the day.
 * It also failed server-side for an evening, and answered the same question
 * three different ways. `groq/compound` was retired. Mistral and OpenRouter
 * search only through paid or separate APIs.
 */
object WebSearch {

    fun supports(id: ProviderId, model: String): Boolean {
        val m = model.lowercase()
        return when (id) {
            ProviderId.GEMINI -> m.startsWith("gemini") && listOf("tts", "image", "embedding", "transcribe", "robotics").none { it in m }
            ProviderId.GROQ, ProviderId.MISTRAL, ProviderId.OPENROUTER -> false
        }
    }

    /** Which provider a search goes to, in order of preference. */
    val PREFERENCE = listOf(ProviderId.GEMINI)

    /** What a search costs on this provider's free tier, in words. */
    fun costNote(id: ProviderId): String = when (id) {
        ProviderId.GEMINI -> "Google allows about 500 web searches a day on the free tier."
        else -> ""
    }

    /**
     * The sources a reply drew on, from Gemini's `groundingMetadata`, with
     * what Google searched for. Deduplicated by address, at most eight, and
     * only http(s) links.
     */
    fun sources(body: String): Pair<List<WebSource>, List<String>> {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList<WebSource>() to emptyList()
        val found = mutableListOf<WebSource>()
        val queries = mutableListOf<String>()
        root.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("groundingMetadata")?.let { g ->
            g.optJSONArray("groundingChunks")?.let { chunks ->
                for (i in 0 until chunks.length()) {
                    val web = chunks.optJSONObject(i)?.optJSONObject("web") ?: continue
                    found += WebSource(web.optString("title"), web.optString("uri"))
                }
            }
            g.optJSONArray("webSearchQueries")?.let { q -> for (i in 0 until q.length()) q.optString(i).takeIf { it.isNotBlank() }?.let(queries::add) }
        }
        val clean = found
            .filter { it.url.startsWith("http://") || it.url.startsWith("https://") }
            .distinctBy { it.url }
            .map { if (it.title.isBlank()) it.copy(title = it.url.substringAfter("://").substringBefore('/')) else it }
            .take(8)
        return clean to queries.distinct()
    }
}
