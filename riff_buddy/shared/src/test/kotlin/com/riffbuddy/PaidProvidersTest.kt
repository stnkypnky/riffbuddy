package com.riffbuddy

import com.riffbuddy.ai.AiPolicy
import com.riffbuddy.ai.AiRequest
import com.riffbuddy.ai.AiResult
import com.riffbuddy.ai.AnthropicApi
import com.riffbuddy.ai.AnthropicProvider
import com.riffbuddy.ai.ChatTurn
import com.riffbuddy.ai.ModelCatalog
import com.riffbuddy.ai.OpenAiCompatProvider
import com.riffbuddy.ai.ProviderConfig
import com.riffbuddy.ai.ProviderId
import com.riffbuddy.ai.ProviderId.ANTHROPIC
import com.riffbuddy.ai.ProviderId.DEEPSEEK
import com.riffbuddy.ai.ProviderId.GEMINI
import com.riffbuddy.ai.ProviderId.GROQ
import com.riffbuddy.ai.ProviderId.MISTRAL
import com.riffbuddy.ai.ProviderId.OPENAI
import com.riffbuddy.ai.SchemaSupport
import com.riffbuddy.ai.WebSearch
import com.riffbuddy.json.JSONObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

/** The paid providers: who gets asked, and what each one is sent and understands. */
class PaidProvidersTest {

    // --- Who is asked -------------------------------------------------------

    @Test
    fun `paid keys are never asked by default`() {
        val keyed = setOf(GROQ, GEMINI, ANTHROPIC, OPENAI)
        assertEquals(listOf(GROQ, GEMINI), AiPolicy.providerOrder(GROQ, keyed, paidFallback = false))
    }

    @Test
    fun `a paid main provider is asked first, then only the free ones`() {
        val keyed = setOf(GROQ, MISTRAL, ANTHROPIC, OPENAI, DEEPSEEK)
        assertEquals(listOf(OPENAI, GROQ, MISTRAL), AiPolicy.providerOrder(OPENAI, keyed, paidFallback = false))
    }

    @Test
    fun `paid fallback adds the other paid providers after every free one`() {
        val keyed = setOf(GROQ, ANTHROPIC, OPENAI, DEEPSEEK)
        assertEquals(listOf(GROQ, ANTHROPIC, OPENAI, DEEPSEEK), AiPolicy.providerOrder(GROQ, keyed, paidFallback = true))
        assertEquals(listOf(DEEPSEEK, GROQ, ANTHROPIC, OPENAI), AiPolicy.providerOrder(DEEPSEEK, keyed, paidFallback = true))
    }

    @Test
    fun `free only is unchanged from before`() {
        val keyed = setOf(GROQ, GEMINI, MISTRAL, ProviderId.OPENROUTER)
        assertEquals(
            listOf(MISTRAL, GROQ, GEMINI, ProviderId.OPENROUTER),
            AiPolicy.providerOrder(MISTRAL, keyed, paidFallback = false),
        )
        // A main provider with no key is skipped, not asked.
        assertEquals(listOf(GROQ, GEMINI), AiPolicy.providerOrder(MISTRAL, setOf(GROQ, GEMINI), paidFallback = false))
    }

    @Test
    fun `only paid keys and none chosen means nobody is asked`() {
        assertTrue(AiPolicy.providerOrder(GROQ, setOf(ANTHROPIC), paidFallback = false).isEmpty())
        assertEquals(listOf(ANTHROPIC), AiPolicy.providerOrder(GROQ, setOf(ANTHROPIC), paidFallback = true))
        assertEquals(listOf(ANTHROPIC), AiPolicy.providerOrder(ANTHROPIC, setOf(ANTHROPIC), paidFallback = false))
    }

    @Test
    fun `the assumed main provider is never a paid one`() {
        assertEquals(GEMINI, AiPolicy.defaultPrimary(setOf(OPENAI, GEMINI)))
        assertEquals(GROQ, AiPolicy.defaultPrimary(setOf(OPENAI)))
    }

    @Test
    fun `free providers are listed before paid ones and paid ones have no free budget`() {
        val firstPaid = ProviderId.entries.indexOfFirst { it.paid }
        assertTrue(ProviderId.entries.drop(firstPaid).all { it.paid })
        ProviderId.PAID.forEach { assertEquals(0, ProviderConfig.defaultFor(it).dailyRequestBudget) }
        assertEquals(GROQ, ProviderId.fromName("nonsense"))
        assertEquals(DEEPSEEK, ProviderId.fromName("DEEPSEEK"))
        // Web search stays with Gemini.
        assertFalse(WebSearch.supports(ANTHROPIC, "claude-opus-5-5"))
        assertFalse(WebSearch.supports(OPENAI, "gpt-6.2-astra"))
    }

    // --- Anthropic: the request ---------------------------------------------

    private val schema = JSONObject(
        """{"type":"object","properties":{"gain":{"type":"integer","minimum":0,"maximum":10},
           "parts":{"type":"array","items":{"type":"object","properties":{"name":{"type":"string"}}}}},
           "required":["gain"]}"""
    )

    @Test
    fun `anthropic body has a top-level system prompt and no sampling parameters`() {
        val request = AiRequest(
            systemPrompt = "You are a guitar teacher.",
            userPrompt = "And the solo?",
            history = listOf(ChatTurn(true, "Tone for the verse?"), ChatTurn(false, "Neck pickup.")),
        )
        val body = AnthropicApi.body(request, "claude-opus-5-5", streaming = false)
        assertEquals("You are a guitar teacher.", body.getString("system"))
        assertEquals(AnthropicApi.MAX_TOKENS, body.getInt("max_tokens"))
        listOf("temperature", "top_p", "top_k", "thinking", "stream").forEach { assertFalse(it, body.has(it)) }
        val messages = body.getJSONArray("messages")
        assertEquals(listOf("user", "assistant", "user"), (0 until messages.length()).map { messages.getJSONObject(it).getString("role") })
        assertTrue((0 until messages.length()).none { messages.getJSONObject(it).getString("role") == "system" })
        assertEquals("medium", body.getJSONObject("output_config").getString("effort"))
    }

    @Test
    fun `anthropic messages start and end with the player and never repeat a side`() {
        val request = AiRequest(
            "", "Again?",
            history = listOf(ChatTurn(false, "Welcome!"), ChatTurn(true, "Hi"), ChatTurn(true, "Hello?")),
        )
        val messages = AnthropicApi.body(request, "claude-haiku-5-5", streaming = false).getJSONArray("messages")
        assertEquals(1, messages.length())
        assertEquals("Hi\n\nHello?\n\nAgain?", messages.getJSONObject(0).getString("content"))
    }

    @Test
    fun `server-side fallback is asked for only on the models that offer it`() {
        val request = AiRequest("s", "q")
        listOf("claude-opus-5-5", "claude-sonnet-5-5").forEach { model ->
            assertEquals("default", AnthropicApi.body(request, model, false).getString("fallbacks"))
            assertEquals(AnthropicApi.FALLBACK_BETA, AnthropicApi.headers("k", model)["anthropic-beta"])
        }
        assertFalse(AnthropicApi.body(request, "claude-haiku-5-5", false).has("fallbacks"))
        assertNull(AnthropicApi.headers("k", "claude-haiku-5-5")["anthropic-beta"])
        val headers = AnthropicApi.headers("k", "claude-opus-5-5")
        assertEquals("k", headers["x-api-key"])
        assertEquals("2026-10-21", headers["anthropic-version"])
        assertEquals("true", headers["anthropic-dangerous-direct-browser-access"])
    }

    @Test
    fun `anthropic structured output shares output_config with effort`() {
        val body = AnthropicApi.body(AiRequest("s", "q", schema = schema), "claude-opus-5-5", streaming = false)
        val output = body.getJSONObject("output_config")
        assertEquals("medium", output.getString("effort"))
        val format = output.getJSONObject("format")
        assertEquals("json_schema", format.getString("type"))
        val sent = format.getJSONObject("schema")
        // Closed objects, all the way down; bounds moved into the description.
        assertEquals(false, sent.opt("additionalProperties"))
        assertEquals(false, sent.getJSONObject("properties").getJSONObject("parts").getJSONObject("items").opt("additionalProperties"))
        val gain = sent.getJSONObject("properties").getJSONObject("gain")
        assertFalse(gain.has("minimum") || gain.has("maximum"))
        assertTrue(gain.getString("description").contains("maximum 10"))
        // The caller's schema is left alone.
        assertTrue(schema.getJSONObject("properties").getJSONObject("gain").has("minimum"))
    }

    @Test
    fun `older claude models are not sent effort, and the connection test asks for little`() {
        assertFalse(AnthropicApi.body(AiRequest("s", "q"), "claude-haiku-4-5", false).has("output_config"))
        val probe = AnthropicApi.body(ModelCatalog.probeSchema(), "claude-opus-5-5", false)
        assertEquals("low", probe.getJSONObject("output_config").getString("effort"))
        val streamed = AnthropicApi.body(AiRequest("s", "q"), "claude-opus-5-5", streaming = true)
        assertEquals(true, streamed.opt("stream"))
        assertEquals(AnthropicApi.MAX_TOKENS_STREAMING, streamed.getInt("max_tokens"))
    }

    // --- Anthropic: the reply -----------------------------------------------

    @Test
    fun `anthropic text is the text blocks only`() {
        val body = """{"stop_reason":"end_turn","content":[
            {"type":"thinking","thinking":"","signature":"abc"},
            {"type":"text","text":"Roll the tone "},
            {"type":"text","text":"back."}]}"""
        assertNull(AnthropicApi.verdict(body, ANTHROPIC))
        assertEquals("Roll the tone back.", AnthropicApi.text(body))
        assertNull(AnthropicApi.text("""{"content":[{"type":"thinking","thinking":""}]}"""))
    }

    @Test
    fun `a refusal is a failure with words, and a cut-off answer is malformed`() {
        val refused = AnthropicApi.verdict(
            """{"stop_reason":"refusal","stop_details":{"category":"cyber","explanation":null},"content":[]}""",
            ANTHROPIC,
        )
        assertTrue(refused is AiResult.Failed)
        assertTrue((refused as AiResult.Failed).message, refused.message.contains("declined") && refused.message.contains("cyber"))
        val bare = AnthropicApi.verdict("""{"stop_reason":"refusal","stop_details":null,"content":[]}""", ANTHROPIC)
        assertEquals("Claude declined to answer this.", (bare as AiResult.Failed).message)
        val cut = AnthropicApi.verdict("""{"stop_reason":"max_tokens","content":[{"type":"text","text":"{\"a\":"}]}""", ANTHROPIC)
        assertTrue(cut is AiResult.Malformed)
    }

    @Test
    fun `anthropic stream reads text deltas and ignores thinking`() {
        assertEquals("Hi", AnthropicApi.streamDelta("""{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Hi"}}"""))
        assertNull(AnthropicApi.streamDelta("""{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"hmm"}}"""))
        assertNull(AnthropicApi.streamDelta("""{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"x"}}"""))
        assertNull(AnthropicApi.streamDelta("""{"type":"message_start","message":{}}"""))
    }

    @Test
    fun `anthropic stream end to end, through the real client`() = runBlocking {
        val events = listOf(
            "event: message_start", """data: {"type":"message_start","message":{"id":"m","content":[]}}""", "",
            "event: content_block_delta", """data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"plan"}}""", "",
            "event: content_block_delta", """data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Bridge "}}""", "",
            "event: ping", """data: {"type":"ping"}""", "",
            "event: content_block_delta", """data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"pickup."}}""", "",
            "event: message_delta", """data: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}""", "",
            "event: message_stop", """data: {"type":"message_stop"}""", "",
        )
        serve(sse(events)) { port ->
            val seen = mutableListOf<String>()
            val result = anthropic(port).stream(AiRequest("s", "q"), "test-key") { seen += it }
            assertEquals("Bridge pickup.", (result as AiResult.Success).text)
            assertEquals(listOf("", "Bridge ", "Bridge pickup."), seen)
        }
    }

    @Test
    fun `anthropic stream refusal and error events end the answer`() = runBlocking {
        val refusal = listOf(
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Sure"}}""", "",
            """data: {"type":"message_delta","delta":{"stop_reason":"refusal","stop_details":{"category":"bio"}}}""", "",
            """data: {"type":"message_stop"}""", "",
        )
        serve(sse(refusal)) { port ->
            val result = anthropic(port).stream(AiRequest("s", "q"), "test-key") {}
            assertTrue(result.toString(), result is AiResult.Failed)
        }
        val overloaded = listOf("""data: {"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""", "")
        serve(sse(overloaded)) { port ->
            val result = anthropic(port).stream(AiRequest("s", "q"), "test-key") {}
            assertTrue(result.toString(), result is AiResult.RateLimited)
        }
    }

    @Test
    fun `anthropic overloaded is busy, and a refusal reply fails over`() = runBlocking {
        serve(http(529, """{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""")) { port ->
            assertTrue(anthropic(port).generate(AiRequest("s", "q"), "k") is AiResult.RateLimited)
        }
        serve(http(200, """{"stop_reason":"refusal","stop_details":{"category":"cyber"},"content":[]}""")) { port ->
            assertTrue(anthropic(port).generate(AiRequest("s", "q"), "k") is AiResult.Failed)
        }
        serve(http(200, """{"stop_reason":"end_turn","content":[{"type":"text","text":"{\"gain\":6,\"name\":\"t\"}"}]}""")) { port ->
            val result = anthropic(port).generate(ModelCatalog.probeSchema(), "k")
            assertTrue(ModelCatalog.probePassed(result))
        }
        serve(http(400, """{"type":"error","error":{"type":"invalid_request_error","message":"bad model"}}""")) { port ->
            assertEquals("bad model", (anthropic(port).generate(AiRequest("s", "q"), "k") as AiResult.Failed).message)
        }
    }

    @Test
    fun `a paid account out of credit is told so, not called busy`() = runBlocking {
        serve(http(429, """{"error":{"message":"You exceeded your quota","type":"insufficient_quota","code":"insufficient_quota"}}""")) { port ->
            val result = OpenAiCompatProvider(ProviderConfig.OPENAI_DEFAULT.copy(baseUrl = "http://127.0.0.1:$port"))
                .generate(AiRequest("s", "q"), "k")
            assertTrue(result.toString(), result is AiResult.Failed && result.message.contains("out of credit"))
        }
    }

    // --- OpenAI and DeepSeek ------------------------------------------------

    @Test
    fun `openai is sent no temperature and a full schema`() {
        val body = OpenAiCompatProvider.body(ProviderConfig.OPENAI_DEFAULT, AiRequest("s", "q", schema = schema), streaming = false)
        assertFalse(body.has("temperature"))
        assertFalse(body.has("max_tokens") || body.has("max_completion_tokens"))
        assertEquals("json_schema", body.getJSONObject("response_format").getString("type"))
        assertEquals("Bearer k", OpenAiCompatProvider.headers(ProviderConfig.OPENAI_DEFAULT, "k")["Authorization"])
    }

    @Test
    fun `deepseek gets json mode with the schema spelled out in the prompt`() {
        val config = ProviderConfig.forModel(DEEPSEEK, null)
        assertFalse(config.supportsJsonSchema)
        val body = OpenAiCompatProvider.body(config, AiRequest("Be brief.", "q", schema = schema), streaming = false)
        assertEquals("json_object", body.getJSONObject("response_format").getString("type"))
        val system = body.getJSONArray("messages").getJSONObject(0)
        assertEquals("system", system.getString("role"))
        assertTrue(system.getString("content").startsWith("Be brief."))
        assertTrue(system.getString("content").contains("JSON schema") && system.getString("content").contains("\"gain\""))
        assertEquals(0.4, body.getDouble("temperature"), 0.0)
        // Without a schema, the prompt is exactly what was asked.
        val plain = OpenAiCompatProvider.body(config, AiRequest("Be brief.", "q"), streaming = true)
        assertEquals("Be brief.", plain.getJSONArray("messages").getJSONObject(0).getString("content"))
        assertEquals(true, plain.opt("stream"))
    }

    @Test
    fun `free providers are sent what they always were`() {
        val body = OpenAiCompatProvider.body(ProviderConfig.GROQ_DEFAULT, AiRequest("s", "q", schema = schema), streaming = false)
        assertEquals(0.4, body.getDouble("temperature"), 0.0)
        assertEquals("s", body.getJSONArray("messages").getJSONObject(0).getString("content"))
        assertEquals("json_schema", body.getJSONObject("response_format").getString("type"))
    }

    // --- Model lists --------------------------------------------------------

    @Test
    fun `openai lists only chat models`() {
        val root = JSONObject(
            """{"data":[{"id":"gpt-6.2-astra"},{"id":"gpt-6.2-sol"},{"id":"text-embedding-3-large"},
              {"id":"gpt-4o-mini-tts"},{"id":"whisper-1"},{"id":"dall-e-3"},{"id":"omni-moderation-latest"},
              {"id":"gpt-realtime"},{"id":"gpt-audio"},{"id":"gpt-image-2"},{"id":"gpt-4o-transcribe"},
              {"id":"gpt-4o-search-preview"},{"id":"o4-mini"},{"id":"babbage-002"},{"id":"sora-2"}]}"""
        )
        val ids = ModelCatalog.parseOpenAi(root).map { it.id }.toSet()
        assertEquals(setOf("gpt-6.2-astra", "gpt-6.2-sol", "o4-mini"), ids)
        assertEquals("gpt-6.2-astra", ModelCatalog.recommendedFirst(ModelCatalog.parseOpenAi(root)).first().id)
    }

    @Test
    fun `anthropic and deepseek lists`() {
        val claude = ModelCatalog.parseAnthropic(JSONObject(
            """{"data":[{"type":"model","id":"claude-sonnet-5-5","display_name":"Claude Sonnet 5.5"},
              {"type":"model","id":"claude-opus-5-5","display_name":"Claude Opus 5.5"}],"has_more":false}"""
        ))
        assertEquals(listOf("claude-sonnet-5-5", "claude-opus-5-5"), claude.map { it.id })
        assertEquals("Claude Opus 5.5", claude[1].label)
        assertEquals("claude-opus-5-5", ModelCatalog.recommendedFirst(claude).first().id)

        val deepseek = ModelCatalog.parseDeepSeek(JSONObject(
            """{"object":"list","data":[{"id":"deepseek-v4-pro","object":"model"},{"id":"deepseek-flash","object":"model"}]}"""
        ))
        assertEquals(listOf("deepseek-flash", "deepseek-v4-pro"), deepseek.map { it.id })
        assertTrue(deepseek.all { it.schema == SchemaSupport.JSON_MODE })
    }

    // --- A one-shot local server, as in StreamingTest ------------------------

    private fun anthropic(port: Int) =
        AnthropicProvider(ProviderConfig.ANTHROPIC_DEFAULT.copy(baseUrl = "http://127.0.0.1:$port"))

    private fun sse(events: List<String>): String =
        "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n" +
            events.joinToString("\n", postfix = "\n")

    private fun http(status: Int, body: String): String {
        val bytes = body.toByteArray()
        return "HTTP/1.1 $status X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n$body"
    }

    private suspend fun serve(response: String, block: suspend (port: Int) -> Unit) {
        ServerSocket(0).use { server ->
            val worker = thread {
                runCatching {
                    server.accept().use { socket ->
                        socket.getInputStream().read(ByteArray(16384))
                        socket.getOutputStream().apply { write(response.toByteArray()); flush() }
                    }
                }
            }
            block(server.localPort)
            worker.join(5_000)
        }
    }
}
