package com.riffbuddy

import com.riffbuddy.ai.AiRequest
import com.riffbuddy.ai.AiResult
import com.riffbuddy.ai.ChatTurn
import com.riffbuddy.ai.GeminiProvider
import com.riffbuddy.ai.OpenAiCompatProvider
import com.riffbuddy.ai.ProviderConfig
import com.riffbuddy.ai.ProviderId
import com.riffbuddy.ai.WebSearch
import com.riffbuddy.ai.prompts.AskPrompt
import com.riffbuddy.ui.tone.MarkdownParser
import com.riffbuddy.ui.tone.MdBlock
import com.riffbuddy.ui.tone.MdSpan
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import kotlin.concurrent.thread

/** The coach's Ask: conversations, length, web search, Markdown. */
class CoachAskTest {

    // ---- web search -------------------------------------------------------

    @Test
    fun `which models can search the web`() {
        // Groq was taken out: its search spent most of a day's allowance at once.
        assertFalse(WebSearch.supports(ProviderId.GROQ, "openai/gpt-oss-120b"))
        assertFalse(WebSearch.supports(ProviderId.GROQ, "openai/gpt-oss-20b"))
        assertTrue(WebSearch.supports(ProviderId.GEMINI, "gemini-flash-latest"))
        assertFalse(WebSearch.supports(ProviderId.GEMINI, "gemini-3.8-flash-tts"))
        assertFalse(WebSearch.supports(ProviderId.GEMINI, "gemma-4-26b-a4b-it"))
        assertFalse(WebSearch.supports(ProviderId.MISTRAL, "mistral-large-latest"))
        assertFalse(WebSearch.supports(ProviderId.OPENROUTER, "openrouter/free"))
    }

    @Test
    fun `gemini's sources come from grounding metadata`() {
        val body = """{"candidates":[{"content":{"parts":[{"text":"1.0.6"}]},
          "groundingMetadata":{"webSearchQueries":["mustang lt25 firmware"],
            "groundingChunks":[{"web":{"uri":"https://vertexaisearch.cloud.google.com/x","title":"fender.com"}},{"web":{"uri":"javascript:x","title":"bad"}}]}}]}"""
        val (sources, queries) = WebSearch.sources(body)
        assertEquals(listOf("fender.com"), sources.map { it.title })
        assertEquals(listOf("mustang lt25 firmware"), queries)
    }

    // ---- what is sent ------------------------------------------------------

    @Test
    fun `a follow-up carries the chat before it, and Groq is never sent a search tool`() = runBlocking {
        val body = captureRequest("""{"choices":[{"message":{"content":"Use the neck pickup."}}]}""") { port ->
            OpenAiCompatProvider(ProviderConfig.GROQ_DEFAULT.copy(baseUrl = "http://127.0.0.1:$port")).generate(
                AiRequest(
                    "system", "What about for the solo?",
                    history = listOf(ChatTurn(true, "Tone for Floods?"), ChatTurn(false, "Bridge pickup, gain 6.")),
                    webSearch = true,
                ),
                "test-key",
            )
        }
        val sent = JSONObject(body)
        val roles = (0 until sent.getJSONArray("messages").length()).map { sent.getJSONArray("messages").getJSONObject(it).getString("role") }
        assertEquals(listOf("system", "user", "assistant", "user"), roles)
        assertFalse(sent.has("tools"))
    }

    @Test
    fun `gemini gets earlier turns as model and user, and google search when asked`() = runBlocking {
        val body = captureRequest("""{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}""") { port ->
            GeminiProvider(ProviderConfig.GEMINI_DEFAULT.copy(baseUrl = "http://127.0.0.1:$port")).generate(
                AiRequest("s", "q", history = listOf(ChatTurn(true, "a"), ChatTurn(false, "b")), webSearch = true),
                "test-key",
            )
        }
        val sent = JSONObject(body)
        val roles = (0 until sent.getJSONArray("contents").length()).map { sent.getJSONArray("contents").getJSONObject(it).getString("role") }
        assertEquals(listOf("user", "model", "user"), roles)
        assertTrue(sent.getJSONArray("tools").getJSONObject(0).has("google_search"))
    }

    @Test
    fun `a model that can't search is never sent the tool`() = runBlocking {
        val body = captureRequest("""{"choices":[{"message":{"content":"ok"}}]}""") { port ->
            OpenAiCompatProvider(ProviderConfig.MISTRAL_DEFAULT.copy(baseUrl = "http://127.0.0.1:$port"))
                .generate(AiRequest("s", "q", webSearch = true), "k")
        }
        assertFalse(JSONObject(body).has("tools"))
    }

    // ---- the prompt ---------------------------------------------------------

    @Test
    fun `the answer length is in the instructions, and what I sent lists the earlier turns`() {
        val ctx = AskPrompt.Context(includeRig = false, includeSong = false, includeLevel = false)
        val deep = AskPrompt.system(ctx, null, null, null, "beginner", "rock", AskPrompt.Length.IN_DEPTH, webSearch = true)
        assertTrue(deep.contains("step by step"))
        assertTrue(deep.contains("search the web"))
        assertTrue(AskPrompt.system(ctx, null, null, null, "beginner", "rock", AskPrompt.Length.SHORT).contains("two or three sentences"))
        val preview = AskPrompt.preview("sys", "and the solo?", listOf(ChatTurn(true, "Tone for Floods?")), webSearch = true)
        assertTrue(preview.contains("Earlier in this chat (1 messages)"))
        assertTrue(preview.contains("You: Tone for Floods?"))
        assertTrue(preview.contains("Web search"))
    }

    // ---- Markdown ---------------------------------------------------------

    @Test
    fun `markdown answers become headings, lists, code and tables`() {
        val blocks = MarkdownParser.blocks(
            """
            ## Gain staging
            Start **low**.

            - Bridge pickup
              - tone at 7
            1. Set gain
            2) Add mids
            ```
            e|--5--|
            ```
            | Knob | Value |
            |---|---|
            | Gain | 6 |
            ---
            """.trimIndent()
        )
        assertEquals(MdBlock.Heading(2, "Gain staging"), blocks[0])
        assertEquals(MdBlock.Paragraph("Start **low**."), blocks[1])
        assertEquals(MdBlock.Bullet("Bridge pickup", 0), blocks[2])
        assertEquals(MdBlock.Bullet("tone at 7", 1), blocks[3])
        assertEquals(MdBlock.Numbered("1", "Set gain", 0), blocks[4])
        assertEquals(MdBlock.Numbered("2", "Add mids", 0), blocks[5])
        assertEquals(MdBlock.Code("e|--5--|"), blocks[6])
        assertEquals(MdBlock.Table(listOf(listOf("Knob", "Value"), listOf("Gain", "6"))), blocks[7])
        assertEquals(MdBlock.Rule, blocks[8])
    }

    @Test
    fun `inline marks become bold, italic, code and links`() {
        val spans = MarkdownParser.spans("Use **gain 6**, *not* `10`, see [Fender](https://fender.com) or https://x.com/a.")
        assertEquals(
            listOf(
                MdSpan.Plain("Use "), MdSpan.Bold("gain 6"), MdSpan.Plain(", "), MdSpan.Italic("not"),
                MdSpan.Plain(" "), MdSpan.Code("10"), MdSpan.Plain(", see "), MdSpan.Link("Fender", "https://fender.com"),
                MdSpan.Plain(" or "), MdSpan.Link("https://x.com/a", "https://x.com/a"), MdSpan.Plain("."),
            ),
            spans,
        )
        // A lone asterisk in prose is not italics.
        assertEquals(listOf(MdSpan.Plain("5 * 3 = 15")), MarkdownParser.spans("5 * 3 = 15"))
    }

    // ---- helpers --------------------------------------------------------------

    /** Serves [response] once and returns the body of the request that came in. */
    private suspend fun captureRequest(response: String, call: suspend (port: Int) -> AiResult): String {
        var body = ""
        ServerSocket(0).use { server ->
            val worker = thread {
                runCatching {
                    server.accept().use { socket ->
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                        var length = 0
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
                        }
                        val chars = CharArray(length)
                        var read = 0
                        while (read < length) { val n = reader.read(chars, read, length - read); if (n < 0) break; read += n }
                        body = String(chars, 0, read)
                        val http = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.toByteArray().size}\r\nConnection: close\r\n\r\n$response"
                        socket.getOutputStream().apply { write(http.toByteArray()); flush() }
                    }
                }
            }
            val result = call(server.localPort)
            assertTrue(result.toString(), result is AiResult.Success)
            worker.join(5_000)
        }
        return body
    }
}
