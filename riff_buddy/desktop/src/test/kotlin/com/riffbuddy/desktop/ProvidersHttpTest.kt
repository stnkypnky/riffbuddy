package com.riffbuddy.desktop

import com.riffbuddy.ai.AiRequest
import com.riffbuddy.ai.AiResult
import com.riffbuddy.ai.GeminiProvider
import com.riffbuddy.ai.OpenAiCompatProvider
import com.riffbuddy.ai.ProviderConfig
import com.riffbuddy.ai.ProviderId
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The providers over real HTTP, against a small local server playing the
 * provider: what a reply, a refusal and a stream turn into, and that a stalled
 * stream or a Cancel ends the request rather than leaving it hanging.
 */
class ProvidersHttpTest {

    private lateinit var server: HttpServer
    private var handler: (HttpExchange) -> Unit = {}
    private val seen = mutableListOf<Pair<String, Map<String, String>>>()
    private val closed = CountDownLatch(1)

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            synchronized(seen) {
                seen += ex.requestURI.toString() to ex.requestHeaders.mapValues { it.value.first() }.mapKeys { it.key.lowercase() }
            }
            ex.requestBody.readBytes()
            try { handler(ex) } catch (_: java.io.IOException) { closed.countDown() } finally { ex.close() }
        }
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private val base get() = "http://127.0.0.1:${server.address.port}/v1"
    private fun openAi(id: ProviderId = ProviderId.GROQ) = OpenAiCompatProvider(ProviderConfig(id, base, "model-x", 100))
    private val request = AiRequest(systemPrompt = "be brief", userPrompt = "hi", timeoutMs = 2_000)

    private fun HttpExchange.reply(code: Int, body: String, headers: Map<String, String> = emptyMap()) {
        headers.forEach { (k, v) -> responseHeaders.add(k, v) }
        val bytes = body.toByteArray()
        sendResponseHeaders(code, bytes.size.toLong())
        responseBody.write(bytes)
    }

    @Test
    fun `an answer comes back with its provider and model`() = runBlocking {
        handler = { it.reply(200, """{"choices":[{"message":{"content":"Play it slower."}}]}""") }
        val result = openAi(ProviderId.OPENROUTER).generate(request, "key-1")
        assertTrue(result is AiResult.Success)
        result as AiResult.Success
        assertEquals("Play it slower.", result.text)
        assertEquals("model-x", result.model)
        val (path, headers) = seen.single()
        assertEquals("/v1/chat/completions", path)
        assertEquals("Bearer key-1", headers["authorization"])
        assertEquals("Riff Buddy", headers["x-title"])
        assertTrue(headers["content-type"]!!.startsWith("application/json"))
    }

    @Test
    fun `Gemini is asked by model and key header`() = runBlocking {
        handler = { it.reply(200, """{"candidates":[{"content":{"parts":[{"text":"Hello"}]}}]}""") }
        val result = GeminiProvider(ProviderConfig(ProviderId.GEMINI, base, "gemini-x", 100)).generate(request, "key-2")
        assertEquals("Hello", (result as AiResult.Success).text)
        val (path, headers) = seen.single()
        assertEquals("/v1/models/gemini-x:generateContent", path)
        assertEquals("key-2", headers["x-goog-api-key"])
    }

    @Test
    fun `a 429 is a rate limit, with its wait`() = runBlocking {
        handler = { it.reply(429, "{}", mapOf("Retry-After" to "7")) }
        assertEquals(AiResult.RateLimited(ProviderId.GROQ, 7), openAi().generate(request, "k"))
    }

    @Test
    fun `an error reply gives the provider's own message`() = runBlocking {
        handler = { it.reply(400, """{"error":{"message":"model not found"}}""") }
        assertEquals(AiResult.Failed(ProviderId.GROQ, "model not found"), openAi().generate(request, "k"))
    }

    @Test
    fun `nobody listening is a network failure, not a crash`() = runBlocking {
        server.stop(0)
        val result = openAi().generate(request, "k")
        assertTrue("$result", result is AiResult.Failed)
    }

    @Test
    fun `a stream arrives piece by piece`() = runBlocking {
        handler = { ex ->
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, 0)
            listOf("Play", " it", " slower.").forEach { piece ->
                ex.responseBody.write("data: {\"choices\":[{\"delta\":{\"content\":\"$piece\"}}]}\n\n".toByteArray())
                ex.responseBody.flush()
                Thread.sleep(30)
            }
            ex.responseBody.write("data: [DONE]\n\n".toByteArray())
        }
        val partials = mutableListOf<String>()
        val result = openAi().stream(request, "k") { partials += it }
        assertEquals("Play it slower.", (result as AiResult.Success).text)
        assertEquals(listOf("", "Play", "Play it", "Play it slower."), partials)
    }

    @Test
    fun `a stream that goes quiet fails instead of hanging`() = runBlocking {
        handler = { ex ->
            ex.sendResponseHeaders(200, 0)
            ex.responseBody.write("data: {\"choices\":[{\"delta\":{\"content\":\"Play\"}}]}\n\n".toByteArray())
            ex.responseBody.flush()
            Thread.sleep(5_000)
        }
        val result = withTimeout(4_000) { openAi().stream(request.copy(timeoutMs = 500), "k") {} }
        assertTrue("$result", result is AiResult.Failed)
    }

    /** Cancel has to mean cancel: the request ends, and the server sees it go. */
    @Test
    fun `canceling a stream closes the connection`() = runBlocking {
        handler = { ex ->
            ex.sendResponseHeaders(200, 0)
            // Keeps writing until the client hangs up, which surfaces here as an IOException.
            repeat(200) {
                ex.responseBody.write(": still thinking\n\n".toByteArray())
                ex.responseBody.flush()
                Thread.sleep(50)
            }
        }
        val job = async { openAi().stream(request.copy(timeoutMs = 10_000), "k") {} }
        delay(300)
        job.cancel()
        withTimeout(1_000) { runCatching { job.await() } }
        assertTrue("the server never saw the connection close", closed.await(3, TimeUnit.SECONDS))
    }
}
