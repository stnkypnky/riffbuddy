package com.riffbuddy.ai

import com.riffbuddy.json.JSONArray
import com.riffbuddy.json.JSONObject
import com.riffbuddy.platform.httpEngine
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import kotlin.time.TimeSource

/**
 * The one HTTP client, because it pools connections. Every request sets its
 * own deadline; connecting gets fifteen seconds whatever the request.
 */
internal val http: HttpClient by lazy {
    HttpClient(httpEngine) {
        install(HttpTimeout) { connectTimeoutMillis = 15_000 }
        // Refusals are read and classified here, not thrown.
        expectSuccess = false
    }
}

/** A POST to send: built per provider, sent the same way by all. */
private class Post(val url: String, val headers: Map<String, String>, val body: String)

/**
 * Google's Generative Language API.
 *
 * Structured output goes in `generationConfig` as `response_mime_type` and
 * `response_schema`, verified against ai.google.dev/api/generate-content. If
 * Google moves it again, [ProviderConfig.baseUrl] and the model name are
 * settings — but a change of *shape* would need code. That is the known soft
 * spot.
 */
class GeminiProvider(override val config: ProviderConfig) : AiProvider {

    override suspend fun generate(request: AiRequest, apiKey: String): AiResult =
        send(post(request, apiKey, "generateContent"), request, config, ResponseParsing::geminiText)

    /**
     * `streamGenerateContent` with `alt=sse`. Without that parameter Google
     * streams a JSON *array* in chunks, which is not parseable until it ends —
     * streaming in name only.
     */
    override suspend fun stream(
        request: AiRequest,
        apiKey: String,
        onPartial: (String) -> Unit,
    ): AiResult =
        sendStreaming(post(request, apiKey, "streamGenerateContent?alt=sse"), request, config, StreamParsing::geminiDelta, onPartial)

    private fun post(request: AiRequest, apiKey: String, method: String): Post {
        val body = JSONObject().apply {
            // Earlier turns first, as Gemini's own roles: "model" for the coach.
            val contents = JSONArray()
            request.history.forEach { turn ->
                contents.put(
                    JSONObject()
                        .put("role", if (turn.fromPlayer) "user" else "model")
                        .put("parts", JSONArray().put(JSONObject().put("text", turn.text)))
                )
            }
            contents.put(
                JSONObject()
                    .put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", request.userPrompt)))
            )
            put("contents", contents)
            if (request.webSearch && request.schema == null && WebSearch.supports(config.id, config.model)) {
                put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            }
            if (request.systemPrompt.isNotBlank()) {
                put(
                    "systemInstruction",
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", request.systemPrompt)),
                    )
                )
            }
            val generation = JSONObject().put("temperature", 0.4)
            request.schema?.let {
                generation.put("response_mime_type", "application/json")
                generation.put("response_schema", it)
            }
            put("generationConfig", generation)
        }

        return Post(
            "${config.baseUrl.trimEnd('/')}/models/${config.model}:$method",
            mapOf("x-goog-api-key" to apiKey),
            body.toString(),
        )
    }
}

/**
 * Groq, Mistral, OpenRouter, OpenAI and DeepSeek, which all speak the OpenAI
 * shape.
 *
 * Only some models honor a full JSON schema; the rest take JSON *mode*,
 * which promises valid JSON but not the right shape. The layer asks for the
 * strongest thing the configured model supports and validates client-side
 * regardless, because "valid JSON of the wrong shape" is the failure that would
 * otherwise reach the faceplate.
 */
class OpenAiCompatProvider(override val config: ProviderConfig) : AiProvider {

    override suspend fun generate(request: AiRequest, apiKey: String): AiResult =
        send(post(request, apiKey, streaming = false), request, config, ResponseParsing::openAiText)

    override suspend fun stream(
        request: AiRequest,
        apiKey: String,
        onPartial: (String) -> Unit,
    ): AiResult =
        sendStreaming(post(request, apiKey, streaming = true), request, config, StreamParsing::openAiDelta, onPartial)

    private fun post(request: AiRequest, apiKey: String, streaming: Boolean): Post =
        Post("${config.baseUrl.trimEnd('/')}/chat/completions", headers(config, apiKey), body(config, request, streaming).toString())

    companion object {

        /**
         * The request body. Where the providers differ is here, and only here:
         *
         * - OpenAI's current models reason before answering, and reasoning
         *   models reject any `temperature` but the default, so none is sent.
         *   No token cap is sent to anyone, so `max_tokens` against
         *   `max_completion_tokens` never comes up.
         * - DeepSeek offers JSON mode but not a schema, and its JSON mode only
         *   works when the prompt itself says "JSON" and shows the shape, so
         *   the schema goes into the system prompt as well.
         */
        internal fun body(config: ProviderConfig, request: AiRequest, streaming: Boolean): JSONObject {
            val jsonModeSchema = request.schema?.takeIf { !config.supportsJsonSchema && config.id == ProviderId.DEEPSEEK }
            val system = if (jsonModeSchema == null) request.systemPrompt
            else listOf(
                request.systemPrompt,
                "Reply with one JSON object only, matching this JSON schema:\n$jsonModeSchema",
            ).filter { it.isNotBlank() }.joinToString("\n\n")

            val messages = JSONArray()
            if (system.isNotBlank()) {
                messages.put(JSONObject().put("role", "system").put("content", system))
            }
            request.history.forEach { turn ->
                messages.put(JSONObject().put("role", if (turn.fromPlayer) "user" else "assistant").put("content", turn.text))
            }
            messages.put(JSONObject().put("role", "user").put("content", request.userPrompt))

            return JSONObject().apply {
                put("model", config.model)
                put("messages", messages)
                if (config.id != ProviderId.OPENAI) put("temperature", 0.4)
                if (streaming) put("stream", true)
                if (request.schema != null) {
                    val format = if (config.supportsJsonSchema) {
                        JSONObject()
                            .put("type", "json_schema")
                            .put(
                                "json_schema",
                                JSONObject().put("name", "tone_card").put("schema", request.schema),
                            )
                    } else {
                        JSONObject().put("type", "json_object")
                    }
                    put("response_format", format)
                }
            }
        }

        internal fun headers(config: ProviderConfig, apiKey: String): Map<String, String> = buildMap {
            put("Authorization", "Bearer $apiKey")
            // OpenRouter's optional app name, shown on the key's activity page
            // so a player can tell these requests from anything else.
            if (config.id == ProviderId.OPENROUTER) put("X-Title", "Riff Buddy")
        }
    }
}

/**
 * Anthropic's Messages API, which is not OpenAI's shape: the system prompt
 * is a top-level field, replies are a list of typed blocks, and a refusal
 * arrives as an ordinary 200 with `stop_reason: "refusal"`.
 *
 * Raw HTTP like every other provider here, because the official SDK is
 * JVM-only and this code also runs in the browser.
 */
class AnthropicProvider(override val config: ProviderConfig) : AiProvider {

    override suspend fun generate(request: AiRequest, apiKey: String): AiResult =
        send(
            post(request, apiKey, streaming = false), request, config, AnthropicApi::text,
            verdict = { body -> AnthropicApi.verdict(body, config.id) },
        )

    override suspend fun stream(
        request: AiRequest,
        apiKey: String,
        onPartial: (String) -> Unit,
    ): AiResult =
        sendStreaming(
            post(request, apiKey, streaming = true), request, config, AnthropicApi::streamDelta, onPartial,
            control = { payload -> AnthropicApi.streamControl(payload, config.id) },
        )

    private fun post(request: AiRequest, apiKey: String, streaming: Boolean): Post = Post(
        "${config.baseUrl.trimEnd('/')}/messages",
        AnthropicApi.headers(apiKey, config.model),
        AnthropicApi.body(request, config.model, streaming).toString(),
    )
}

/** What a stream's next event means, beyond the text it may carry. */
internal sealed interface StreamControl {
    data object Continue : StreamControl
    /** The provider said it has finished; stop reading. */
    data object Finish : StreamControl
    /** The provider said this is not an answer after all. */
    data class Stop(val result: AiResult) : StreamControl
}

/**
 * The bit both adapters share: send, classify, parse.
 *
 * Classification matters more than it looks. A 429 must be distinguishable from
 * a dropped connection, because one means "try the other provider now" and the
 * other means "everything AI is unavailable" — and the error messages depend
 * on telling them apart.
 */
private suspend fun send(
    post: Post,
    request: AiRequest,
    config: ProviderConfig,
    extract: (String) -> String?,
    /** A judgment on the whole body before any text is read from it, such as a refusal. */
    verdict: (String) -> AiResult? = { null },
): AiResult {
    val provider = config.id
    val started = TimeSource.Monotonic.markNow()
    return try {
        prepare(post) { timeout { requestTimeoutMillis = request.timeoutMs } }.execute { response ->
            refusal(response, provider)?.let { return@execute it }
            val elapsed = started.elapsedNow().inWholeMilliseconds
            val body = response.bodyAsText()
            verdict(body)?.let { return@execute it }
            val result = finish(extract(body), request, config, elapsed)
            if (request.webSearch && result is AiResult.Success) {
                val (sources, queries) = WebSearch.sources(body)
                result.copy(sources = sources, searchQueries = queries)
            } else result
        }
    } catch (e: IOException) {
        AiResult.Failed(provider, e.message ?: "Network error")
    }
}

/**
 * The same request as a server-sent-event stream.
 *
 * Everything after the last byte is identical to [send] — the same refusal
 * codes, the same repetition check, the same result type — because a streamed
 * answer that turns out to be a repetition loop has to be retried exactly like
 * one that arrived in a single piece. What differs is only that the player has
 * been reading it while it arrived.
 *
 * A whole-request deadline is the wrong shape for a stream: an answer that is
 * still arriving is healthy however long it has been arriving for, and cutting
 * it off at thirty seconds would kill exactly the long answers streaming exists
 * to make bearable. What must not happen is silence, so the deadline is on the
 * gap between lines instead — nothing for the request's timeout and the
 * connection is dead. Canceling the coroutine cancels the request, since every
 * read suspends rather than holding a thread, so Cancel stops the request
 * rather than merely stopping showing it.
 */
private suspend fun sendStreaming(
    post: Post,
    request: AiRequest,
    config: ProviderConfig,
    delta: (String) -> String?,
    onPartial: (String) -> Unit,
    /** What each event means beyond its text: an end, a refusal, an error. */
    control: (String) -> StreamControl = { StreamControl.Continue },
): AiResult {
    val provider = config.id
    val started = TimeSource.Monotonic.markNow()
    return try {
        prepare(post) {}.execute { response ->
            refusal(response, provider)?.let { return@execute it }

            val text = StringBuilder()
            onPartial("")
            val channel = response.bodyAsChannel()
            while (true) {
                val line = withTimeoutOrNull(request.timeoutMs) { channel.readUTF8Line() ?: END }
                    ?: return@execute AiResult.Failed(provider, "No reply for ${request.timeoutMs / 1000}s")
                if (line === END) break
                val payload = StreamParsing.sseData(line) ?: continue
                if (StreamParsing.isDone(payload)) break
                when (val c = control(payload)) {
                    StreamControl.Continue -> Unit
                    StreamControl.Finish -> break
                    is StreamControl.Stop -> return@execute c.result
                }
                val chunk = delta(payload) ?: continue
                text.append(chunk)
                onPartial(text.toString())
            }

            finish(text.toString(), request, config, started.elapsedNow().inWholeMilliseconds)
        }
    } catch (e: IOException) {
        AiResult.Failed(provider, e.message ?: "Network error")
    }
}

/** What a finished stream reads as, so that it differs from a line that never came. */
private const val END = "\u0000end of stream"

private suspend fun prepare(post: Post, more: HttpRequestBuilder.() -> Unit) =
    http.preparePost(post.url) {
        post.headers.forEach { (k, v) -> header(k, v) }
        contentType(ContentType.Application.Json)
        setBody(post.body)
        more()
    }

/** A reply that is not an answer at all, or null if the stream may proceed. */
private suspend fun refusal(response: HttpResponse, provider: ProviderId): AiResult? {
    val status = response.status.value
    if (status == 429 || status == 529) {
        // A paid account that has run out of credit also answers 429, and
        // that is not "busy, try later": it needs the player to top up.
        if (provider.paid && status == 429) {
            val error = runCatching { JSONObject(response.bodyAsText()).optJSONObject("error") }.getOrNull()
            if (error?.optString("code") == "insufficient_quota" || error?.optString("type") == "insufficient_quota") {
                return AiResult.Failed(provider, "${provider.label} says the account is out of credit.")
            }
        }
        // 529 is Anthropic's "overloaded": as busy as a rate limit, and the
        // same thing helps, which is asking another provider.
        return AiResult.RateLimited(
            provider,
            ResponseParsing.retryAfterSeconds(response.headers["retry-after"]),
        )
    }
    if (!response.status.isSuccess()) {
        // Never echo the body wholesale: a provider error can quote the
        // request, and the request carries no key but the habit is worth
        // keeping: keys never go in logs.
        val reason = runCatching {
            JSONObject(response.bodyAsText()).optJSONObject("error")?.optString("message")
        }.getOrNull()?.takeIf { it.isNotBlank() }
        return AiResult.Failed(provider, reason ?: "HTTP $status")
    }
    return null
}

/** The judgment on a complete answer, however it arrived. */
private fun finish(
    text: String?,
    request: AiRequest,
    config: ProviderConfig,
    elapsedMs: Long,
): AiResult {
    val provider = config.id
    if (text.isNullOrBlank()) return AiResult.Malformed(provider, "No text in the reply")

    // A repetition loop is malformed even when it parses. Treated as
    // Malformed rather than Failed so AiPolicy retries once and then
    // tries the other provider — which is exactly the right response,
    // since the loop is a property of this model on this prompt.
    if (AiPolicy.looksDegenerate(text)) {
        return AiResult.Malformed(provider, "The model got stuck repeating itself")
    }

    val json = if (request.schema != null) {
        ResponseParsing.extractJson(text)
            ?: return AiResult.Malformed(provider, "Reply was not JSON")
    } else null

    // Provider and model travel with every result and are stored on the
    // card, because when a card is wrong the first question is which model
    // produced it.
    return AiResult.Success(
        text = text,
        json = json,
        provider = provider,
        model = config.model,
        elapsedMs = elapsedMs,
    )
}
