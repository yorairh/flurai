package com.flurai.app.core.chat

import com.flurai.app.core.model.JlptLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatPromptTest {
    @Test
    fun parsesAllThreePartsIncludingContinuationLines() {
        val turn = parseTurn(
            """
            REPLY: こんにちは。お名前は何ですか。
            TRANSLATION: Hello. What is your name?
            CORRECTION: 私は学生です。 Use は, not が, for the topic.
            """.trimIndent(),
        )
        assertEquals("こんにちは。お名前は何ですか。", turn.reply)
        assertEquals("Hello. What is your name?", turn.translation)
        assertEquals("私は学生です。 Use は, not が, for the topic.", turn.correction)
    }

    @Test
    fun noneMeansNoCorrection() {
        listOf("NONE", "none", "None.", "  NONE  ").forEach {
            assertNull(it, parseTurn("REPLY: はい。\nTRANSLATION: Yes.\nCORRECTION: $it").correction)
        }
    }

    @Test
    fun labelsAreCaseInsensitiveAndMultilineReplyIsKept() {
        val turn = parseTurn("reply: 一行目\n二行目\ntranslation: Line one\ncorrection: NONE")
        assertEquals("一行目\n二行目", turn.reply)
        assertEquals("Line one", turn.translation)
    }

    @Test
    fun anUnformattedAnswerBecomesTheReply() {
        val turn = parseTurn("  はい、そうです。  ")
        assertEquals("はい、そうです。", turn.reply)
        assertNull(turn.translation)
        assertNull(turn.correction)
    }

    @Test
    fun promptCarriesLevelScenarioAndWhatTheLearnerKnows() {
        val p = systemPrompt(
            LearnerContext(JlptLevel.N4, knownWords = listOf("水", "食べる"), knownGrammar = listOf("〜たい"), weakGrammar = listOf("〜ので")),
            scenario("cafe"),
        )
        assertTrue(p.contains("JLPT N4"))
        assertTrue(p.contains("café"))
        assertTrue(p.contains("水、食べる"))
        assertTrue(p.contains("〜たい"))
        assertTrue(p.contains("〜ので"))
        assertTrue(p.contains("REPLY:") && p.contains("TRANSLATION:") && p.contains("CORRECTION:"))
    }

    @Test
    fun beginnersGetTheSimplestInstructionsAndUnknownScenariosFallBack() {
        assertTrue(systemPrompt(LearnerContext(JlptLevel.Beginner), scenario("free")).contains("absolute beginner"))
        assertEquals("free", scenario("nope").id)
        assertEquals(scenarios.size, scenarios.map { it.id }.toSet().size)
    }
}
