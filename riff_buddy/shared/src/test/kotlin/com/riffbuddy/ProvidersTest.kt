package com.riffbuddy

import com.riffbuddy.ai.ModelCatalog
import com.riffbuddy.ai.ProviderConfig
import com.riffbuddy.ai.ProviderId
import com.riffbuddy.ai.SchemaSupport
import com.riffbuddy.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The four free providers: what they list, what they default to, how to get a key. */
class ProvidersTest {

    @Test
    fun `Groq defaults to gpt-oss-120b, which left Llama behind on the free tier`() {
        assertEquals("openai/gpt-oss-120b", ProviderConfig.GROQ_DEFAULT.model)
        assertTrue(ProviderConfig.forModel(ProviderId.GROQ, null).supportsJsonSchema)
        // Any other Groq model gets JSON mode, or Groq rejects the call.
        assertFalse(ProviderConfig.forModel(ProviderId.GROQ, "qwen/qwen3.6-27b").supportsJsonSchema)
    }

    @Test
    fun `every provider says where its free key is and what to suggest`() {
        ProviderId.entries.forEach { p ->
            assertTrue(p.keyUrl, p.keyUrl.startsWith("https://"))
            assertTrue(p.keySteps.isNotBlank())
            // A paid provider says so first, in words, wherever its terms appear.
            if (p.paid) assertTrue(p.freeTier, p.freeTier.startsWith("Paid:"))
            else assertTrue(p.freeTier, p.freeTier.contains("no card"))
            assertEquals(p.recommendedModel, ProviderConfig.defaultFor(p).model)
            assertEquals(p, ProviderConfig.defaultFor(p).id)
        }
    }

    @Test
    fun `Mistral lists only its chat models`() {
        val root = JSONObject(
            """{"data":[
              {"id":"mistral-large-latest","capabilities":{"completion_chat":true}},
              {"id":"mistral-embed","capabilities":{"completion_chat":false}},
              {"id":"mistral-ocr-latest","capabilities":{"completion_chat":true}},
              {"id":"mistral-moderation-latest","capabilities":{"completion_chat":true}},
              {"id":"mistral-large-latest","capabilities":{"completion_chat":true}}]}"""
        )
        val models = ModelCatalog.parseMistral(root)
        assertEquals(listOf("mistral-large-latest"), models.map { it.id })
        assertEquals(SchemaSupport.FULL, models[0].schema)
    }

    @Test
    fun `OpenRouter offers its free router first and only free models`() {
        val root = JSONObject(
            """{"data":[
              {"id":"openai/gpt-5","supported_parameters":["structured_outputs"]},
              {"id":"meta/llama-4:free","supported_parameters":["response_format"]},
              {"id":"qwen/qwen3:free","supported_parameters":["structured_outputs","response_format"]}]}"""
        )
        val models = ModelCatalog.parseOpenRouter(root)
        assertEquals(listOf("openrouter/free", "qwen/qwen3:free", "meta/llama-4:free"), models.map { it.id })
        assertEquals(SchemaSupport.JSON_MODE, models.last().schema)
    }

    @Test
    fun `the recommended model comes first and says why`() {
        val models = ModelCatalog.parseGroq(JSONObject(
            """{"data":[{"id":"qwen/qwen3.6-27b"},{"id":"openai/gpt-oss-20b"},{"id":"openai/gpt-oss-120b"}]}"""
        ))
        val ordered = ModelCatalog.recommendedFirst(models)
        assertEquals("openai/gpt-oss-120b", ordered.first().id)
        assertTrue(ordered.first().note.startsWith("Recommended"))
    }
}
