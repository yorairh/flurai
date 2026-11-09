package com.flurai.app.core.reading

import com.flurai.app.core.content.SentenceEntity
import com.flurai.app.core.content.WordEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ReadingTest {
    private fun word(id: Int, expr: String, jlpt: Int = 5, other: String? = null) = WordEntity(
        id = id, expression = expr, reading = expr, otherForms = other, meanings = "m$id",
        jlpt = jlpt, genkiLesson = null, jmdictId = null, partsOfSpeech = null,
    )

    private val lexicon = Lexicon(
        listOf(word(1, "学生"), word(2, "学"), word(3, "本"), word(4, "は"), word(5, "読む", other = "読む; 讀む"), word(6, "学生", jlpt = 3)),
    )

    @Test
    fun matchesLongestWordFirstAndKeepsPlainText() {
        val tokens = lexicon.tokenize("私は学生です")
        assertEquals(listOf("私は", "学生", "です"), tokens.map { it.text })
        assertEquals(1, tokens[1].word?.id) // the easier N5 entry wins over the N3 duplicate
        assertNull(tokens[0].word)
    }

    @Test
    fun loneKanaIsNotTappable() {
        assertTrue(lexicon.tokenize("は").all { it.word == null })
    }

    @Test
    fun oneKanjiWordsAndOtherFormsMatch() {
        assertEquals(3, lexicon.tokenize("本").single().word?.id)
        assertEquals(5, lexicon.tokenize("讀む").single().word?.id)
    }

    @Test
    fun tokensCoverTheWholeSentence() {
        val text = "学生が本を読むのは良い。"
        assertEquals(text, lexicon.tokenize(text).joinToString("") { it.text })
    }

    @Test
    fun sentenceLevelIsTheHardestWordAndRejectsUnknownKanji() {
        val lex = Lexicon(listOf(word(1, "学生", jlpt = 5), word(2, "本", jlpt = 3)))
        assertEquals(3, sentenceLevel(lex.tokenize("学生の本です")))
        assertNull(sentenceLevel(lex.tokenize("学生は釈放された"))) // 釈放 is not in the lists
        assertNull(sentenceLevel(lex.tokenize("これはです")))      // no known word
    }

    private fun sentence(id: Int, en: String) = SentenceEntity(id, "文$id", en, true)

    @Test
    fun sentenceQuestionOffersFourDifferentTranslations() {
        val pool = listOf(sentence(1, "I read a book."), sentence(2, "He is a student."), sentence(3, "It is raining."), sentence(4, "She sings well."), sentence(5, "I read a book."))
        repeat(10) { seed ->
            val q = sentenceQuestion(pool[0], pool, 5, Random(seed))!!
            assertEquals("I read a book.", q.correctOption)
            assertEquals(4, q.options.map { it.lowercase() }.toSet().size)
        }
    }

    @Test
    fun sentenceQuestionNeedsEnoughOtherSentences() {
        val pool = listOf(sentence(1, "A"), sentence(2, "B"), sentence(3, "C"))
        assertNull(sentenceQuestion(pool[0], pool, 5, Random(1)))
    }
}
