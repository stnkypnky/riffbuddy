package com.riffbuddy.ai

import com.riffbuddy.json.JSONObject
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess

/** A model the provider says it serves right now. */
data class ModelInfo(
    val id: String,
    val label: String,
    val provider: ProviderId,
    /** What we know about JSON-schema support before actually trying it. */
    val schema: SchemaSupport = SchemaSupport.UNKNOWN,
    val note: String = "",
)

enum class SchemaSupport {
    /** Documented to honor a full JSON schema. */
    FULL,
    /** JSON mode only: valid JSON, no guarantee of shape. */
    JSON_MODE,
    /** Not established — the Test button settles it (see [ModelCatalog.probeSchema]). */
    UNKNOWN,
}

/**
 * Asks each provider what it actually serves.
 *
 * A bundled list of models goes stale silently: providers retire models
 * without notice, and a model ID that quietly stopped existing shows up as an
 * unexplained failure rather than as a message anyone can act on.
 *
 * Filtering matters as much as listing. Both providers serve plenty of models
 * that cannot do this job at all — Gemini's Live models speak a bidirectional
 * WebSocket API rather than `generateContent`, and Groq serves speech and
 * content-guard models. Picking one of those would look like a broken app.
 */
object ModelCatalog {

    suspend fun list(config: ProviderConfig, apiKey: String): Result<List<ModelInfo>> =
        runCatching {
            // Anthropic pages its list, twenty at a time by default.
            val query = if (config.id == ProviderId.ANTHROPIC) "?limit=1000" else ""
            val response = http.get("${config.baseUrl.trimEnd('/')}/models$query") {
                when (config.id) {
                    ProviderId.GEMINI -> header("x-goog-api-key", apiKey)
                    ProviderId.ANTHROPIC -> AnthropicApi.headers(apiKey, model = "").forEach { (k, v) -> header(k, v) }
                    else -> header("Authorization", "Bearer $apiKey")
                }
                timeout { requestTimeoutMillis = 15_000 }
            }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                val detail = runCatching {
                    JSONObject(body).optJSONObject("error")?.optString("message")
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: "HTTP ${response.status.value}"
                error(detail)
            }
            val root = JSONObject(body)
            when (config.id) {
                ProviderId.GEMINI -> parseGemini(root)
                ProviderId.GROQ -> parseGroq(root)
                ProviderId.MISTRAL -> parseMistral(root)
                ProviderId.OPENROUTER -> parseOpenRouter(root)
                ProviderId.ANTHROPIC -> parseAnthropic(root)
                ProviderId.OPENAI -> parseOpenAi(root)
                ProviderId.DEEPSEEK -> parseDeepSeek(root)
            }.let(::recommendedFirst).let(::markWebSearch)
        }

    /**
     * Anthropic lists only Claude models, every one of which can chat and
     * take a schema, newest first, which is the order worth keeping.
     */
    internal fun parseAnthropic(root: JSONObject): List<ModelInfo> {
        val data = root.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val o = data.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").takeIf { it.startsWith("claude") } ?: return@mapNotNull null
            ModelInfo(
                id = id,
                label = o.optString("display_name").ifBlank { id },
                provider = ProviderId.ANTHROPIC,
                schema = SchemaSupport.FULL,
            )
        }.distinctBy { it.id }
    }

    /**
     * OpenAI's list is everything the key can reach: images, speech,
     * transcription, embeddings, moderation, realtime voice and more. Only
     * the GPT and o-series chat models are kept, minus the variants that
     * answer only on another API.
     */
    internal fun parseOpenAi(root: JSONObject): List<ModelInfo> {
        val data = root.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val o = data.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").takeIf { isOpenAiChatModel(it) } ?: return@mapNotNull null
            ModelInfo(id, id, ProviderId.OPENAI, SchemaSupport.FULL)
        }.distinctBy { it.id }.sortedByDescending { it.id }
    }

    internal fun isOpenAiChatModel(id: String): Boolean {
        val lower = id.lowercase()
        val family = lower.startsWith("gpt-") || lower.startsWith("chatgpt-") || Regex("^o[0-9]").containsMatchIn(lower)
        if (!family) return false
        val excluded = listOf(
            "embedding", "tts", "whisper", "dall-e", "moderation", "realtime", "audio", "image",
            "transcribe", "search", "computer-use", "codex", "instruct", "deep-research", "cyber",
        )
        return excluded.none { it in lower }
    }

    /** DeepSeek serves a handful of chat models, all on JSON mode rather than a schema. */
    internal fun parseDeepSeek(root: JSONObject): List<ModelInfo> {
        val data = root.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val id = data.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ModelInfo(id, id, ProviderId.DEEPSEEK, SchemaSupport.JSON_MODE, note = "JSON mode only — shape is checked on this end")
        }.distinctBy { it.id }.sortedBy { it.id }
    }

    /**
     * Gemini lists `supportedGenerationMethods` per model, which is exactly the
     * filter needed: a model that does not offer `generateContent` cannot be
     * used here, and that is what excludes the Live models without having to
     * keep a list of their names.
     */
    internal fun parseGemini(root: JSONObject): List<ModelInfo> {
        val models = root.optJSONArray("models") ?: return emptyList()
        return (0 until models.length()).mapNotNull { i ->
            val o = models.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name").removePrefix("models/")
            if (name.isBlank()) return@mapNotNull null

            val methods = o.optJSONArray("supportedGenerationMethods")
            val supportsGenerate = methods != null &&
                (0 until methods.length()).any { methods.optString(it) == "generateContent" }
            if (!supportsGenerate) return@mapNotNull null

            // Embedding and retrieval models answer generateContent on paper but
            // are not chat models; they have no use here.
            if (name.contains("embedding") || name.contains("aqa")) return@mapNotNull null

            val isGemma = name.startsWith("gemma")
            ModelInfo(
                id = name,
                label = o.optString("displayName").ifBlank { name },
                provider = ProviderId.GEMINI,
                // Gemma on this API has historically not honored response_schema
                // or system instructions. Rather than assert either way, it is
                // marked unknown and the Test button finds out on the player's own key.
                schema = if (isGemma) SchemaSupport.UNKNOWN else SchemaSupport.FULL,
                note = if (isGemma) "Open model — very high free limits, but check it honors the schema" else "",
            )
        }.sortedBy { it.id }
    }

    /** Groq speaks the OpenAI list shape: `{"data":[{"id":…}]}`. */
    internal fun parseGroq(root: JSONObject): List<ModelInfo> {
        val data = root.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val o = data.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (!isChatModel(id)) return@mapNotNull null

            // The gpt-oss family is the one Groq documents for json_schema; the
            // rest get json_object, which promises valid JSON and nothing about
            // its shape.
            val full = id.contains("gpt-oss")
            ModelInfo(
                id = id,
                label = id.substringAfterLast('/'),
                provider = ProviderId.GROQ,
                schema = if (full) SchemaSupport.FULL else SchemaSupport.JSON_MODE,
                note = when {
                    full -> "Honors a full JSON schema"
                    id.contains("compound") -> "Agentic — can use tools; slower, and JSON mode only"
                    else -> "JSON mode only — shape is checked on this end"
                },
            )
        }.sortedByDescending { it.schema == SchemaSupport.FULL }
    }

    /**
     * Mistral lists `capabilities.completion_chat` per model, which excludes
     * its embedding, OCR and moderation models without a list of names. Every
     * chat model takes a JSON schema.
     */
    internal fun parseMistral(root: JSONObject): List<ModelInfo> {
        val data = root.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val o = data.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (o.optJSONObject("capabilities")?.optBoolean("completion_chat", false) != true) return@mapNotNull null
            if (!isChatModel(id) || id.contains("ocr")) return@mapNotNull null
            ModelInfo(id, id, ProviderId.MISTRAL, SchemaSupport.FULL)
        }.distinctBy { it.id }.sortedBy { it.id }
    }

    /**
     * OpenRouter lists hundreds of models, nearly all paid. Only the free ones
     * (ids ending ":free") are offered, plus its `openrouter/free` router,
     * and each says whether it takes structured output.
     */
    internal fun parseOpenRouter(root: JSONObject): List<ModelInfo> {
        val data = root.optJSONArray("data") ?: return emptyList()
        val free = (0 until data.length()).mapNotNull { i ->
            val o = data.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").takeIf { it.endsWith(":free") } ?: return@mapNotNull null
            val params = o.optJSONArray("supported_parameters")
            val supported = if (params == null) emptySet() else (0 until params.length()).map { params.optString(it) }.toSet()
            ModelInfo(
                id = id,
                label = o.optString("name").ifBlank { id },
                provider = ProviderId.OPENROUTER,
                schema = when {
                    "structured_outputs" in supported -> SchemaSupport.FULL
                    "response_format" in supported -> SchemaSupport.JSON_MODE
                    else -> SchemaSupport.UNKNOWN
                },
            )
        }.sortedByDescending { it.schema == SchemaSupport.FULL }
        val router = ModelInfo(
            "openrouter/free", "Free models router", ProviderId.OPENROUTER, SchemaSupport.FULL,
            note = "Picks a free model that can answer",
        )
        return listOf(router) + free.filter { it.id != router.id }
    }

    /**
     * Models that can search the web say so, in the list and in the note
     * under the chosen one, so it's visible before choosing.
     */
    internal fun markWebSearch(models: List<ModelInfo>): List<ModelInfo> = models.map { m ->
        if (!WebSearch.supports(m.provider, m.id)) m
        else m.copy(
            label = "${m.label} · web search",
            note = listOf(m.note, "Can search the web (Search the web in Ask).").filter { it.isNotBlank() }.joinToString(" "),
        )
    }

    /** Each provider's recommended model at the top of its list, marked as such. */
    internal fun recommendedFirst(models: List<ModelInfo>): List<ModelInfo> {
        val provider = models.firstOrNull()?.provider ?: return models
        val (recommended, rest) = models.partition { it.id == provider.recommendedModel }
        return recommended.map { it.copy(note = "Recommended — ${provider.recommendedWhy}.") } + rest
    }

    /**
     * Groq serves speech, transcription and guard models alongside chat ones.
     *
     * The guard models are the trap worth naming: `llama-prompt-guard` has a
     * 14,400/day limit against everything else's 1,000, so anything sorting by
     * daily allowance lands straight on a content classifier that cannot hold a
     * conversation.
     */
    private fun isChatModel(id: String): Boolean {
        val lower = id.lowercase()
        // Orpheus is Groq's text-to-speech family, listed like a chat model.
        val excluded = listOf("whisper", "tts", "guard", "embedding", "moderation", "distil-whisper", "orpheus")
        return excluded.none { lower.contains(it) }
    }

    /**
     * The smallest request that settles whether a model really honors a schema.
     *
     * The docs do not say — Google's rate-limit page now defers to AI Studio,
     * and neither provider publishes per-model schema support in a form worth
     * trusting. Asking the model itself, with the player's own key, is the only
     * answer that is actually true for them.
     */
    fun probeSchema(): AiRequest = AiRequest(
        systemPrompt = "Reply only with JSON matching the schema.",
        userPrompt = "Set gain to 6 and name the tone 'test'.",
        schema = JSONObject(
            """
            {"type":"object",
             "properties":{"gain":{"type":"integer"},"name":{"type":"string"}},
             "required":["gain","name"]}
            """.trimIndent()
        ),
        feature = PROBE_FEATURE,
        timeoutMs = 15_000,
    )

    const val PROBE_FEATURE = "schema-probe"

    /** True when the reply is JSON of the shape [probeSchema] asked for. */
    fun probePassed(result: AiResult): Boolean {
        val json = (result as? AiResult.Success)?.json ?: return false
        return json.has("gain") && json.has("name")
    }
}
