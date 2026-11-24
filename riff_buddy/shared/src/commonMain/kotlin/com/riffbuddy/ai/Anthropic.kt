package com.riffbuddy.ai

import com.riffbuddy.json.JSONArray
import com.riffbuddy.json.JSONObject

/**
 * The shape of Anthropic's Messages API, kept apart from the HTTP so every
 * rule below can be tested against captured bodies.
 *
 * Several things that are harmless elsewhere are errors here, on API version
 * 2026-10-21 and the current models:
 * - `temperature`, `top_p` and `top_k` are rejected outright, so none is sent.
 * - Thinking cannot be turned off on the newest models, and a thinking budget
 *   is rejected; `thinking` is left out, and `output_config.effort` is what
 *   sets how hard the model works.
 * - A trailing assistant message (a "prefill") is rejected, so a conversation
 *   always ends on the player's turn.
 */
object AnthropicApi {

    const val VERSION = "2026-10-21"

    /** The beta that lets a declined request be retried on another model, on Anthropic's side. */
    const val FALLBACK_BETA = "server-side-fallback-2026-07-01"

    /**
     * Room for the model's thinking as well as the reply: thinking counts
     * against this limit even though its text is not returned. A streamed
     * answer may go longer, since nothing waits for it to finish.
     */
    const val MAX_TOKENS = 16_000
    const val MAX_TOKENS_STREAMING = 64_000

    /**
     * Models whose safety filters can decline a harmless request, and which
     * accept `fallbacks: "default"`: Anthropic re-runs a declined request on
     * the model it recommends for that kind of decline, inside the same call,
     * so a false positive costs an answer from a sibling model rather than
     * no answer. Haiku has no server-side fallback, and sending one there
     * would be rejected.
     */
    fun usesFallbacks(model: String): Boolean = model in setOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-opus-5")

    /** Models that take `output_config.effort`; older ones reject it. */
    fun supportsEffort(model: String): Boolean =
        Regex("^claude-(opus|sonnet|haiku|fable|mythos)-5").containsMatchIn(model) ||
            Regex("^claude-opus-4-[5-8]").containsMatchIn(model) ||
            model.startsWith("claude-sonnet-4-6")

    fun headers(apiKey: String, model: String): Map<String, String> = buildMap {
        put("x-api-key", apiKey)
        put("anthropic-version", VERSION)
        // Without this the API refuses a call made from a web page. The key
        // is the player's own and already lives in their browser, which is
        // the risk the header's name warns about; elsewhere it changes nothing.
        put("anthropic-dangerous-direct-browser-access", "true")
        if (usesFallbacks(model)) put("anthropic-beta", FALLBACK_BETA)
    }

    fun body(request: AiRequest, model: String, streaming: Boolean): JSONObject = JSONObject().apply {
        put("model", model)
        put("max_tokens", if (streaming) MAX_TOKENS_STREAMING else MAX_TOKENS)
        if (request.systemPrompt.isNotBlank()) put("system", request.systemPrompt)
        put("messages", messages(request))

        val output = JSONObject()
        if (supportsEffort(model)) {
            // Medium is the everyday level; the connection test only needs
            // the cheapest answer that proves the key and the schema.
            output.put("effort", if (request.feature == ModelCatalog.PROBE_FEATURE) "low" else "medium")
        }
        request.schema?.let {
            output.put("format", JSONObject().put("type", "json_schema").put("schema", compatibleSchema(it)))
        }
        if (output.length() > 0) put("output_config", output)

        if (usesFallbacks(model)) put("fallbacks", "default")
        if (streaming) put("stream", true)
    }

    /**
     * Earlier turns, then the question, as strictly alternating user and
     * assistant messages that start and end with the player. Two turns from
     * the same side in a row (a question that failed and was asked again)
     * are joined rather than sent as a pair the API might refuse.
     */
    internal fun messages(request: AiRequest): JSONArray {
        val turns = mutableListOf<Pair<Boolean, String>>()
        (request.history + ChatTurn(fromPlayer = true, text = request.userPrompt)).forEach { turn ->
            if (turns.isEmpty() && !turn.fromPlayer) return@forEach
            val last = turns.lastOrNull()
            if (last != null && last.first == turn.fromPlayer) {
                turns[turns.lastIndex] = last.first to last.second + "\n\n" + turn.text
            } else {
                turns += turn.fromPlayer to turn.text
            }
        }
        return JSONArray().also { out ->
            turns.forEach { (fromPlayer, text) ->
                out.put(JSONObject().put("role", if (fromPlayer) "user" else "assistant").put("content", text))
            }
        }
    }

    /**
     * The schema as Anthropic's structured output accepts it.
     *
     * It requires every object to be closed (`additionalProperties: false`)
     * and does not take numeric or length bounds. Those bounds move into the
     * description, where the model still reads them, and the answer is
     * checked on this end as it is for every provider.
     */
    fun compatibleSchema(schema: JSONObject): JSONObject = JSONObject(schema.toString()).also(::adapt)

    private val UNSUPPORTED = listOf(
        "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf",
        "minLength", "maxLength", "maxItems",
    )

    private fun adapt(node: JSONObject) {
        if (node.optString("type") == "object" || node.has("properties")) {
            node.put("additionalProperties", false)
        }
        val notes = mutableListOf<String>()
        UNSUPPORTED.forEach { key -> if (node.has(key)) notes += "$key ${node.remove(key)}" }
        if (node.optInt("minItems", 0) > 1) notes += "minItems ${node.remove("minItems")}"
        if (notes.isNotEmpty()) {
            val description = node.optString("description")
            node.put("description", listOf(description, "(${notes.joinToString(", ")})").filter { it.isNotBlank() }.joinToString(" "))
        }
        node.keys().forEach { key ->
            when (val child = node.opt(key)) {
                is JSONObject -> adapt(child)
                is JSONArray -> for (i in 0 until child.length()) child.optJSONObject(i)?.let(::adapt)
            }
        }
    }

    /**
     * The reply's text: the `text` blocks, joined. A reply can open with
     * `thinking` blocks (empty under the default display) or a note that a
     * fallback model took over; neither is the answer.
     */
    fun text(body: String): String? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val content = root.optJSONArray("content") ?: return null
        val text = buildString {
            for (i in 0 until content.length()) {
                val block = content.optJSONObject(i) ?: continue
                if (block.optString("type") == "text") append(block.optString("text"))
            }
        }
        return text.ifBlank { null }
    }

    /**
     * Read before the content, because a refusal is a successful HTTP reply.
     * A refusal is final for this provider; the next provider may answer. A
     * reply cut off at the length limit is treated as malformed, like any
     * other answer that ran away, so it is retried once and never cached.
     */
    fun verdict(body: String, provider: ProviderId): AiResult? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return stopResult(root.optString("stop_reason"), root.optJSONObject("stop_details"), provider)
    }

    private fun stopResult(stopReason: String, details: JSONObject?, provider: ProviderId): AiResult? = when (stopReason) {
        "refusal" -> AiResult.Failed(provider, refusalMessage(details))
        "max_tokens" -> AiResult.Malformed(provider, "The answer was cut off at the length limit")
        else -> null
    }

    /** Plain words for a decline; the category and explanation are informational and often absent. */
    internal fun refusalMessage(details: JSONObject?): String {
        val explanation = details?.optString("explanation")?.takeIf { it.isNotBlank() }
        val category = details?.optString("category")?.takeIf { it.isNotBlank() && it != "null" }
        return buildString {
            append("Claude declined to answer this")
            if (category != null) append(" (its safety filter flagged it as $category)")
            append(".")
            if (explanation != null) append(" ").append(explanation)
        }
    }

    /** A streamed event's new text: `text_delta` only, never thinking or signatures. */
    fun streamDelta(payload: String): String? {
        val root = runCatching { JSONObject(payload) }.getOrNull() ?: return null
        if (root.optString("type") != "content_block_delta") return null
        val delta = root.optJSONObject("delta") ?: return null
        if (delta.optString("type") != "text_delta") return null
        return delta.optString("text").takeIf { it.isNotEmpty() }
    }

    /** What a streamed event says about the stream itself. */
    internal fun streamControl(payload: String, provider: ProviderId): StreamControl {
        val root = runCatching { JSONObject(payload) }.getOrNull() ?: return StreamControl.Continue
        return when (root.optString("type")) {
            "message_stop" -> StreamControl.Finish
            "message_delta" -> {
                val delta = root.optJSONObject("delta")
                stopResult(delta?.optString("stop_reason").orEmpty(), delta?.optJSONObject("stop_details"), provider)
                    ?.let { StreamControl.Stop(it) } ?: StreamControl.Continue
            }
            "error" -> {
                val error = root.optJSONObject("error")
                when (error?.optString("type")) {
                    "overloaded_error", "rate_limit_error" -> StreamControl.Stop(AiResult.RateLimited(provider, null))
                    else -> StreamControl.Stop(
                        AiResult.Failed(provider, error?.optString("message")?.takeIf { it.isNotBlank() } ?: "The stream failed"),
                    )
                }
            }
            else -> StreamControl.Continue
        }
    }
}
