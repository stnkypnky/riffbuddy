package com.riffbuddy

import com.riffbuddy.ai.AiRequest
import com.riffbuddy.ai.AiResult
import com.riffbuddy.ai.GeminiProvider
import com.riffbuddy.ai.OpenAiCompatProvider
import com.riffbuddy.ai.ProviderConfig
import com.riffbuddy.ai.ResponseParsing
import com.riffbuddy.ai.StreamParsing
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Streaming, tested against a real socket rather than a mock.
 *
 * The parsing is easy to get right; what is easy to get wrong is the loop
 * around it — a stream that stops at the first blank line, a cancel that
 * leaves a thread reading a socket forever. A twenty-line HTTP server on
 * localhost exercises the actual OkHttp path both providers use.
 */
class StreamingTest {

    @Test
    fun `sse lines that are not data are skipped`() {
        assertNull(StreamParsing.sseData(""))
        assertNull(StreamParsing.sseData(": keep-alive"))
        assertNull(StreamParsing.sseData("event: message"))
        assertNull(StreamParsing.sseData("data:"))
        assertEquals("{\"a\":1}", StreamParsing.sseData("data: {\"a\":1}"))
        assertEquals("{\"a\":1}", StreamParsing.sseData("data:{\"a\":1}"))
    }

    @Test
    fun `openai deltas read content and ignore the role-only first chunk`() {
        assertNull(StreamParsing.openAiDelta("""{"choices":[{"delta":{"role":"assistant"}}]}"""))
        assertEquals("Hel", StreamParsing.openAiDelta("""{"choices":[{"delta":{"content":"Hel"}}]}"""))
        // A space is content. Trimming or blank-checking it glues words together.
        assertEquals(" ", StreamParsing.openAiDelta("""{"choices":[{"delta":{"content":" "}}]}"""))
        assertNull(StreamParsing.openAiDelta("""{"choices":[{"delta":{},"finish_reason":"stop"}]}"""))
        assertTrue(StreamParsing.isDone("[DONE]"))
    }

    @Test
    fun `gemini deltas are whole response objects`() {
        assertEquals(
            "Use the neck pickup",
            StreamParsing.geminiDelta(
                """{"candidates":[{"content":{"parts":[{"text":"Use the neck pickup"}],"role":"model"}}]}"""
            ),
        )
    }

    /**
     * Gemma 4 streams its whole plan ("Persona: guitar teacher… Constraint:
     * no tab…") ahead of the answer, as parts marked thought. They are the
     * model talking to itself, not to the player.
     */
    @Test
    fun `gemini thought parts are not the answer`() {
        val thinking = """{"candidates":[{"content":{"parts":[{"text":"User wants tips.","thought":true}]}}]}"""
        assertNull(StreamParsing.geminiDelta(thinking))

        val mixed = """{"candidates":[{"content":{"parts":[""" +
            """{"text":"Draft {not json}","thought":true},{"text":"{\"a\":1}"}]}}]}"""
        assertEquals("""{"a":1}""", ResponseParsing.geminiText(mixed))
    }

    @Test
    fun `groq stream arrives piece by piece and ends as one answer`() = runBlocking {
        val events = listOf(
            """data: {"choices":[{"delta":{"role":"assistant"}}]}""",
            "",
            """data: {"choices":[{"delta":{"content":"Roll the "}}]}""",
            "",
            ": keep-alive",
            """data: {"choices":[{"delta":{"content":"tone knob back."}}]}""",
            "",
            "data: [DONE]",
            "",
        )
        serve(sse(events)) { port ->
            val seen = mutableListOf<String>()
            val result = OpenAiCompatProvider(ProviderConfig.GROQ_DEFAULT.copy(baseUrl = "http://127.0.0.1:$port"))
                .stream(AiRequest("s", "q"), "test-key") { seen += it }

            assertTrue(result.toString(), result is AiResult.Success)
            assertEquals("Roll the tone knob back.", (result as AiResult.Success).text)
            // "" first, which clears anything a previous attempt showed; then
            // the text so far, growing.
            assertEquals(listOf("", "Roll the ", "Roll the tone knob back."), seen)
        }
    }

    @Test
    fun `gemini stream ends when the connection closes`() = runBlocking {
        val events = listOf(
            """data: {"candidates":[{"content":{"parts":[{"text":"Bridge "}]}}]}""",
            "",
            """data: {"candidates":[{"content":{"parts":[{"text":"pickup."}]}}]}""",
            "",
        )
        serve(sse(events)) { port ->
            val result = GeminiProvider(ProviderConfig.GEMINI_DEFAULT.copy(baseUrl = "http://127.0.0.1:$port"))
                .stream(AiRequest("s", "q"), "test-key") {}
            assertEquals("Bridge pickup.", (result as AiResult.Success).text)
        }
    }

    @Test
    fun `a rate limit is still a rate limit when streaming`() = runBlocking {
        val response = "HTTP/1.1 429 Too Many Requests\r\nretry-after: 7\r\n" +
            "Content-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}"
        serve(response) { port ->
            val result = OpenAiCompatProvider(ProviderConfig.GROQ_DEFAULT.copy(baseUrl = "http://127.0.0.1:$port"))
                .stream(AiRequest("s", "q"), "test-key") {}
            assertEquals(7, (result as AiResult.RateLimited).retryAfterSeconds)
        }
    }

    /**
     * Cancel works. The server sends one piece and then says nothing,
     * forever; a canceled request has to come back anyway, and well before the
     * thirty-second silence timeout would have rescued it.
     */
    @Test
    fun `cancel stops a stream that has gone quiet`() = runBlocking {
        val head = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\"Hmm\"}}]}\n\n"
        val release = CountDownLatch(1)
        ServerSocket(0).use { server ->
            val worker = thread {
                runCatching {
                    server.accept().use { socket ->
                        socket.getInputStream().read(ByteArray(8192))
                        socket.getOutputStream().apply { write(head.toByteArray()); flush() }
                        release.await(20, TimeUnit.SECONDS)
                    }
                }
            }
            val provider = OpenAiCompatProvider(ProviderConfig.GROQ_DEFAULT.copy(baseUrl = "http://127.0.0.1:${server.localPort}"))
            val firstPiece = CountDownLatch(1)
            val started = System.nanoTime()
            val job = async(kotlinx.coroutines.Dispatchers.IO) {
                provider.stream(AiRequest("s", "q"), "test-key") { if (it.isNotEmpty()) firstPiece.countDown() }
            }
            assertTrue("the first piece never arrived", firstPiece.await(10, TimeUnit.SECONDS))
            delay(200)
            job.cancel()
            withTimeout(5_000) { runCatching { job.await() } }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue("cancel took ${elapsedMs}ms", elapsedMs < 10_000)
            release.countDown()
            worker.join(5_000)
        }
    }

    private fun sse(events: List<String>): String =
        "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n" +
            events.joinToString("\n", postfix = "\n")

    /** One connection, one canned response, then the socket closes. */
    private suspend fun serve(response: String, block: suspend (port: Int) -> Unit) {
        ServerSocket(0).use { server ->
            val worker = thread {
                runCatching {
                    server.accept().use { socket ->
                        socket.getInputStream().read(ByteArray(8192))
                        socket.getOutputStream().apply { write(response.toByteArray()); flush() }
                    }
                }
            }
            block(server.localPort)
            worker.join(5_000)
        }
    }
}
