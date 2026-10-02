package com.riffbuddy.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

private val JSON_MEDIA = "application/json".toMediaType()

/** Shared HTTP client. One instance, because OkHttp pools connections. */
internal fun httpClient(timeoutMs: Long): OkHttpClient = OkHttpClient.Builder()
    .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
    .connectTimeout(15, TimeUnit.SECONDS)
    .build()

/**
 * The client for a streamed answer, which needs the opposite deadline.
 *
 * A whole-call timeout is the wrong shape for a stream: an answer that is still
 * arriving is healthy however long it has been arriving for, and cutting it off
 * at thirty seconds would kill exactly the long answers streaming exists to
 * make bearable. What must not happen is silence, so the deadline moves to the
 * gap between reads — nothing for [idleMs] and the connection is dead.
 */
internal fun streamingClient(idleMs: Long): OkHttpClient = OkHttpClient.Builder()
    .callTimeout(0, TimeUnit.MILLISECONDS)
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(idleMs, TimeUnit.MILLISECONDS)
    .build()

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
        withContext(Dispatchers.IO) {
            send(post(request, apiKey, "generateContent"), request, config, ResponseParsing::geminiText)
        }

    /**
     * `streamGenerateContent` with `alt=sse`. Without that parameter Google
     * streams a JSON *array* in chunks, which is not parseable until it ends —
     * streaming in name only.
     */
    override suspend fun stream(
        request: AiRequest,
        apiKey: String,
        onPartial: (String) -> Unit,
    ): AiResult = withContext(Dispatchers.IO) {
        val http = post(request, apiKey, "streamGenerateContent?alt=sse")
        sendStreaming(http, request, config, StreamParsing::geminiDelta, onPartial)
    }

    private fun post(request: AiRequest, apiKey: String, method: String): Request {
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

        return Request.Builder()
            .url("${config.baseUrl.trimEnd('/')}/models/${config.model}:$method")
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
    }
}

/**
 * Groq, Mistral and OpenRouter, which all speak the OpenAI shape.
 *
 * Only some models honor a full JSON schema; the rest take JSON *mode*,
 * which promises valid JSON but not the right shape. The layer asks for the
 * strongest thing the configured model supports and validates client-side
 * regardless, because "valid JSON of the wrong shape" is the failure that would
 * otherwise reach the faceplate.
 */
class OpenAiCompatProvider(override val config: ProviderConfig) : AiProvider {

    override suspend fun generate(request: AiRequest, apiKey: String): AiResult =
        withContext(Dispatchers.IO) {
            send(post(request, apiKey, streaming = false), request, config, ResponseParsing::openAiText)
        }

    override suspend fun stream(
        request: AiRequest,
        apiKey: String,
        onPartial: (String) -> Unit,
    ): AiResult = withContext(Dispatchers.IO) {
        val http = post(request, apiKey, streaming = true)
        sendStreaming(http, request, config, StreamParsing::openAiDelta, onPartial)
    }

    private fun post(request: AiRequest, apiKey: String, streaming: Boolean): Request {
        val messages = JSONArray()
        if (request.systemPrompt.isNotBlank()) {
            messages.put(JSONObject().put("role", "system").put("content", request.systemPrompt))
        }
        request.history.forEach { turn ->
            messages.put(JSONObject().put("role", if (turn.fromPlayer) "user" else "assistant").put("content", turn.text))
        }
        messages.put(JSONObject().put("role", "user").put("content", request.userPrompt))

        val body = JSONObject().apply {
            put("model", config.model)
            put("messages", messages)
            put("temperature", 0.4)
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

        return Request.Builder()
            .url("${config.baseUrl.trimEnd('/')}/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            // OpenRouter's optional app name, shown on the key's activity page
            // so a player can tell these requests from anything else.
            .apply { if (config.id == ProviderId.OPENROUTER) addHeader("X-Title", "Riff Buddy") }
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
    }
}

/**
 * The bit both adapters share: send, classify, parse.
 *
 * Classification matters more than it looks. A 429 must be distinguishable from
 * a dropped connection, because one means "try the other provider now" and the
 * other means "everything AI is unavailable" — and the error messages depend
 * on telling them apart.
 */
private fun send(
    http: Request,
    request: AiRequest,
    config: ProviderConfig,
    extract: (String) -> String?,
): AiResult {
    val provider = config.id
    val started = System.nanoTime()
    return try {
        httpClient(request.timeoutMs).newCall(http).execute().use { response ->
            refusal(response, provider)?.let { return it }
            val elapsed = (System.nanoTime() - started) / 1_000_000
            val body = response.body.string()
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
 */
private suspend fun sendStreaming(
    http: Request,
    request: AiRequest,
    config: ProviderConfig,
    delta: (String) -> String?,
    onPartial: (String) -> Unit,
): AiResult {
    val started = System.nanoTime()
    val call = streamingClient(request.timeoutMs).newCall(http)

    // Cancel has to mean cancel. A thread parked on a socket read does not
    // notice that its coroutine was canceled, so something else has to close
    // the call from the outside — which is what makes the Cancel button stop
    // the request rather than merely stop showing it.
    //
    // That something is a second coroutine that does nothing but wait. The
    // obvious tool, `job.invokeOnCompletion`, does not work: it fires when the
    // job *completes*, and the job cannot complete while this thread is stuck
    // in the read it was supposed to interrupt. A child coroutine, by contrast,
    // is canceled the moment its parent starts canceling, and it runs on
    // another thread, so its `finally` can reach in and close the socket.
    return coroutineScope {
        val watcher = launch {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            readStream(call, request, config, delta, onPartial, started)
        } finally {
            watcher.cancel()
        }
    }
}

private suspend fun readStream(
    call: Call,
    request: AiRequest,
    config: ProviderConfig,
    delta: (String) -> String?,
    onPartial: (String) -> Unit,
    started: Long,
): AiResult {
    val provider = config.id
    return try {
        call.execute().use { response ->
            refusal(response, provider)?.let { return it }

            val text = StringBuilder()
            onPartial("")
            val source = response.body.source()
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = source.readUtf8Line() ?: break
                val payload = StreamParsing.sseData(line) ?: continue
                if (StreamParsing.isDone(payload)) break
                val chunk = delta(payload) ?: continue
                text.append(chunk)
                onPartial(text.toString())
            }

            finish(text.toString(), request, config, (System.nanoTime() - started) / 1_000_000)
        }
    } catch (e: IOException) {
        AiResult.Failed(provider, e.message ?: "Network error")
    }
}

/** A reply that is not an answer at all, or null if the stream may proceed. */
private fun refusal(response: Response, provider: ProviderId): AiResult? {
    if (response.code == 429) {
        return AiResult.RateLimited(
            provider,
            ResponseParsing.retryAfterSeconds(response.header("retry-after")),
        )
    }
    if (!response.isSuccessful) {
        // Never echo the body wholesale: a provider error can quote the
        // request, and the request carries no key but the habit is worth
        // keeping: keys never go in logs.
        val reason = runCatching {
            JSONObject(response.body.string()).optJSONObject("error")?.optString("message")
        }.getOrNull()?.takeIf { it.isNotBlank() }
        return AiResult.Failed(provider, reason ?: "HTTP ${response.code}")
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
