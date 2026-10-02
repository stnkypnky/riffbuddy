package com.riffbuddy

import com.riffbuddy.domain.HelpCatalog
import com.riffbuddy.domain.HelpTopic
import com.riffbuddy.ui.help.helpTopicFor
import com.riffbuddy.ui.tuner.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "?" helper's answers. */
class HelpTest {

    private val everything = HelpTopic.entries.flatMap { HelpCatalog.forTopic(it) }

    @Test
    fun `every screen has an FAQ with nothing blank`() {
        HelpTopic.entries.forEach { topic ->
            val faqs = HelpCatalog.forTopic(topic)
            // Up to 16: Theory teaches, so it has the most. Past that, the
            // sheet is a manual, and search is the better way in.
            assertTrue("$topic has ${faqs.size}", faqs.size in 4..16)
            faqs.forEach {
                assertTrue(it.question.isNotBlank() && it.answer.isNotBlank())
                assertTrue("not a question: ${it.question}", it.question.endsWith("?"))
            }
            assertEquals("$topic repeats a question", faqs.size, faqs.map { it.question }.toSet().size)
        }
    }

    @Test
    fun `the screens that teach explain more than the ones that don't`() {
        fun words(t: HelpTopic) = HelpCatalog.forTopic(t).sumOf { it.answer.split(" ").size }
        listOf(HelpTopic.THEORY, HelpTopic.METRONOME, HelpTopic.BACKING, HelpTopic.COACH).forEach {
            assertTrue("$it", words(it) > words(HelpTopic.SETTINGS))
        }
    }

    @Test
    fun `search ignores case and needs every word`() {
        val dropD = HelpCatalog.search("DROP d")
        assertTrue(dropD.any { (topic, faq) -> topic == HelpTopic.TUNER && "Drop D" in faq.question })
        assertTrue(HelpCatalog.search("drop zebra").isEmpty())
    }

    @Test
    fun `search reaches across topics`() {
        val topics = HelpCatalog.search("streak").map { it.first }.toSet()
        assertTrue(topics.size >= 2)
    }

    @Test
    fun `an empty search shows nothing rather than everything`() {
        assertTrue(HelpCatalog.search("").isEmpty())
        assertTrue(HelpCatalog.search("   ").isEmpty())
    }

    @Test
    fun `answers are in US English`() {
        // Word stems, so "colours" and "cancelled" are caught too.
        val british = listOf("colour", "favourite", "practise", "cancelled", "centre", "dialling", "metre", "analyse")
        everything.forEach { faq ->
            val text = (faq.question + " " + faq.answer).lowercase()
            british.forEach { assertTrue("\"$it\" in: ${faq.question}", it !in text) }
        }
    }

    @Test
    fun `every tool has its own help`() {
        assertEquals(Tool.entries.size, Tool.entries.map(::helpTopicFor).toSet().size)
    }
}
