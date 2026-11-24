package com.riffbuddy

import com.riffbuddy.ai.ModelCatalog
import com.riffbuddy.ai.SchemaSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.riffbuddy.json.JSONObject

class ModelCatalogTest {

    /**
     * The filter that keeps Live models out.
     *
     * They are a genuine trap: a "flash live" model is the newest and fastest
     * thing on the list, and it cannot answer a `generateContent` call at all —
     * it speaks a bidirectional WebSocket API for voice agents. Filtering on the
     * capability the provider reports means no list of banned names to maintain.
     */
    @Test
    fun `gemini models without generateContent are excluded`() {
        val json = JSONObject(
            """
            {"models":[
              {"name":"models/gemini-flash","displayName":"Flash",
               "supportedGenerationMethods":["generateContent","countTokens"]},
              {"name":"models/gemini-flash-live","displayName":"Flash Live",
               "supportedGenerationMethods":["bidiGenerateContent"]},
              {"name":"models/text-embedding-004","displayName":"Embedding",
               "supportedGenerationMethods":["embedContent"]}
            ]}
            """.trimIndent()
        )
        val models = ModelCatalog.parseGemini(json).map { it.id }
        assertEquals(listOf("gemini-flash"), models)
    }

    @Test
    fun `embedding models are excluded even if they claim generateContent`() {
        val json = JSONObject(
            """
            {"models":[
              {"name":"models/embedding-gecko",
               "supportedGenerationMethods":["generateContent"]},
              {"name":"models/gemini-pro",
               "supportedGenerationMethods":["generateContent"]}
            ]}
            """.trimIndent()
        )
        assertEquals(listOf("gemini-pro"), ModelCatalog.parseGemini(json).map { it.id })
    }

    /** Gemma is offered but flagged, because its schema support is unestablished. */
    @Test
    fun `gemma is listed as unknown for schema support`() {
        val json = JSONObject(
            """
            {"models":[
              {"name":"models/gemma-3-27b-it","supportedGenerationMethods":["generateContent"]},
              {"name":"models/gemini-flash","supportedGenerationMethods":["generateContent"]}
            ]}
            """.trimIndent()
        )
        val byId = ModelCatalog.parseGemini(json).associateBy { it.id }
        assertEquals(SchemaSupport.UNKNOWN, byId.getValue("gemma-3-27b-it").schema)
        assertEquals(SchemaSupport.FULL, byId.getValue("gemini-flash").schema)
        assertTrue(byId.getValue("gemma-3-27b-it").note.contains("free limits"))
    }

    /**
     * The guard-model trap. `llama-prompt-guard` allows 14,400 requests a day
     * against everything else's 1,000, so any instinct to sort by daily
     * allowance lands on a content classifier that cannot hold a conversation.
     */
    @Test
    fun `groq speech and guard models are excluded`() {
        val json = JSONObject(
            """
            {"data":[
              {"id":"openai/gpt-oss-120b"},
              {"id":"meta-llama/llama-prompt-guard-2-86m"},
              {"id":"whisper-large-v3"},
              {"id":"playai-tts"},
              {"id":"groq/compound"}
            ]}
            """.trimIndent()
        )
        val ids = ModelCatalog.parseGroq(json).map { it.id }
        assertTrue(ids.contains("openai/gpt-oss-120b"))
        assertTrue(ids.contains("groq/compound"))
        assertFalse("guard models cannot chat", ids.contains("meta-llama/llama-prompt-guard-2-86m"))
        assertFalse(ids.contains("whisper-large-v3"))
        assertFalse(ids.contains("playai-tts"))
    }

    @Test
    fun `gpt-oss is marked as honoring a full schema and sorts first`() {
        val json = JSONObject(
            """
            {"data":[{"id":"groq/compound"},{"id":"openai/gpt-oss-120b"}]}
            """.trimIndent()
        )
        val models = ModelCatalog.parseGroq(json)
        assertEquals("openai/gpt-oss-120b", models.first().id)
        assertEquals(SchemaSupport.FULL, models.first().schema)
        assertEquals(SchemaSupport.JSON_MODE, models.last().schema)
        assertTrue(models.last().note.contains("Agentic"))
    }

    @Test
    fun `an empty or broken list parses to nothing rather than throwing`() {
        assertTrue(ModelCatalog.parseGemini(JSONObject("{}")).isEmpty())
        assertTrue(ModelCatalog.parseGroq(JSONObject("{}")).isEmpty())
    }

    /** The probe only passes on a reply that really has the requested shape. */
    @Test
    fun `the schema probe checks shape, not just valid json`() {
        val provider = com.riffbuddy.ai.ProviderId.GEMINI
        fun success(body: String?) = com.riffbuddy.ai.AiResult.Success(
            text = body ?: "", json = body?.let { JSONObject(it) },
            provider = provider, model = "m", elapsedMs = 1,
        )
        assertTrue(ModelCatalog.probePassed(success("""{"gain":6,"name":"test"}""")))
        assertFalse("valid JSON of the wrong shape must fail", ModelCatalog.probePassed(success("""{"answer":"ok"}""")))
        assertFalse(ModelCatalog.probePassed(success(null)))
        assertFalse(ModelCatalog.probePassed(com.riffbuddy.ai.AiResult.Offline))
    }
}
