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
    fun anAffixIsNeverMatchedAsAWord() {
        // 来 is listed as らい～ ("next ~"): 来ました must not be read らい
        val next = word(20, "来").copy(reading = "らい～", meanings = "next ~", jlpt = 3)
        val counter = word(21, "～人").copy(reading = "～にん")
        val lex = Lexicon(listOf(next, counter, word(22, "人")))
        assertNull(lex.tokenize("来ました").first().word)
        assertEquals(22, lex.tokenize("人").single().word?.id)
    }

    @Test
    fun partsFromTokensUseTheWordsReading() {
        val tokens = lexicon.tokenize("学生")
        assertEquals(1, rubyPartsOf(tokens).size)
        assertEquals("学生", rubyPartsOf(tokens).single().text)
    }

    @Test
    fun theCommonReadingWinsBetweenWordsSharingASpelling() {
        // 私 is both わたし (id 713, common) and わたくし (id 714, formal) at N5: わたし must be the one shown.
        val watashi = word(713, "私").copy(reading = "わたし")
        val watakushi = word(714, "私").copy(reading = "わたくし")
        for (order in listOf(listOf(watashi, watakushi), listOf(watakushi, watashi))) {
            assertEquals("わたし", Lexicon(order).tokenize("私は").first().word?.reading)
        }
    }

    private fun sentence(id: Int, en: String, jp: String = "文$id") = SentenceEntity(id, jp, en, true, 5)

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
    fun wrongOptionsComeFromSentencesThatShareAWord() {
        val target = sentence(1, "I read a book.", jp = "私は本を読む。")
        val sharing = listOf(
            sentence(2, "She bought a book yesterday.", jp = "彼女は昨日本を買った。"),
            sentence(3, "A book is on the desk.", jp = "机の上に本がある。"),
            sentence(4, "This book is old.", jp = "この本は古い。"),
        )
        val unrelated = (5..30).map { sentence(it, "Another sentence $it.", jp = "別の文$it") }
        repeat(20) { seed ->
            val q = sentenceQuestion(target, listOf(target) + unrelated + sharing, 5, Random(seed), keywords = listOf("本"))!!
            val wrong = q.options.filter { it != q.correctOption }
            assertEquals(setOf("She bought a book yesterday.", "A book is on the desk.", "This book is old."), wrong.toSet())
        }
    }

    @Test
    fun fewRelatedSentencesAreTopedUpWithOthers() {
        val target = sentence(1, "I read a book.", jp = "私は本を読む。")
        val pool = listOf(target, sentence(2, "A book is on the desk.", jp = "机の上に本がある。")) + (3..10).map { sentence(it, "Other $it.") }
        val q = sentenceQuestion(target, pool, 5, Random(1), keywords = listOf("本"))!!
        assertTrue("A book is on the desk." in q.options)
        assertEquals(4, q.options.toSet().size)
    }

    @Test
    fun sentenceQuestionNeedsEnoughOtherSentences() {
        val pool = listOf(sentence(1, "A"), sentence(2, "B"), sentence(3, "C"))
        assertNull(sentenceQuestion(pool[0], pool, 5, Random(1)))
    }
}
