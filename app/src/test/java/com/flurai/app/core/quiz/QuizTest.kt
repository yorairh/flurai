package com.flurai.app.core.quiz

import com.flurai.app.core.content.KanjiEntity
import com.flurai.app.core.content.WordEntity
import com.flurai.app.core.model.JlptLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class QuizTest {
    private fun word(id: Int, expr: String, meaning: String, pos: String = "n", jlpt: Int = 5) = WordEntity(
        id = id, expression = expr, reading = expr, otherForms = null, meanings = meaning,
        jlpt = jlpt, genkiLesson = null, jmdictId = null, partsOfSpeech = pos,
    )

    private fun kanji(c: String, on: String, kun: String, jlpt: Int = 5) = KanjiEntity(
        literal = c, jlpt = jlpt, meanings = "x", onyomi = on, kunyomi = kun, strokeCount = 3, grade = 1, frequency = 1,
    )

    private val pool = listOf(
        word(1, "水", "water"), word(2, "火", "fire"), word(3, "木", "tree, wood"),
        word(4, "金", "gold; money"), word(5, "食べる", "to eat", pos = "v1"),
    )

    @Test
    fun glossIsTheFirstMeaning() {
        assertEquals("heaven", firstGloss("heaven, the sky"))
        assertEquals("gold", firstGloss("gold; money"))
        assertEquals("Sunday", firstGloss("Sunday"))
    }

    @Test
    fun wordQuestionHasFourDistinctOptionsAndTheRightAnswer() {
        repeat(20) { seed ->
            val q = wordQuestion(pool[0], pool, Random(seed))!!
            assertEquals(4, q.options.size)
            assertEquals(4, q.options.map { it.lowercase() }.toSet().size)
            assertEquals("water", q.correctOption)
            assertEquals(1, q.wordId)
        }
    }

    @Test
    fun wordQuestionNeedsEnoughDistinctDistractors() {
        assertNull(wordQuestion(pool[0], pool.take(3), Random(1)))
        val sameGloss = listOf(word(1, "a", "water"), word(2, "b", "Water"), word(3, "c", "water"), word(4, "d", "water"))
        assertNull(wordQuestion(sameGloss[0], sameGloss, Random(1)))
    }

    @Test
    fun readingShownOnlyWhenItDiffersFromTheWord() {
        val kanjiWord = word(9, "日曜日", "Sunday").copy(reading = "にちようび")
        val q = wordQuestion(kanjiWord, pool + kanjiWord, Random(3))!!
        assertEquals("にちようび", q.hint)
        assertNull(wordQuestion(pool[0], pool, Random(3))!!.hint)
    }

    @Test
    fun listeningQuestionSpeaksTheReadingAndHidesTheWrittenWordUntilAnswered() {
        val kanjiWord = word(9, "日曜日", "Sunday").copy(reading = "にちようび")
        val q = listeningQuestion(kanjiWord, pool + kanjiWord, Random(3))!!
        assertEquals(QuestionKind.WordListening, q.kind)
        assertEquals("にちようび", q.prompt)
        assertNull(q.hint)
        assertEquals("日曜日（にちようび）", q.note)
        assertEquals("Sunday", q.correctOption)
        assertEquals(9, q.wordId)
    }

    @Test
    fun mainReadingPrefersKunyomiWithoutOkuriganaDots() {
        assertEquals("たべ", mainReading(kanji("食", "ショク", "た.べる")) ?.take(2))
        assertEquals("うじ", mainReading(kanji("氏", "シ", "うじ、-うじ")))
        assertEquals("ダイ", mainReading(kanji("第", "ダイ、テイ", "")))
        assertNull(mainReading(kanji("?", "", "")))
    }

    @Test
    fun kanjiQuestionKeepsDistractorsInTheSameScript() {
        val kanjiPool = listOf(
            kanji("氏", "シ", "うじ"), kanji("統", "トウ", "す.べる"), kanji("保", "ホ", "たも.つ"),
            kanji("第", "ダイ", ""), kanji("結", "ケツ", "むす.ぶ"), kanji("派", "ハ", ""),
            kanji("山", "サン", "やま"), kanji("川", "セン", "かわ"),
        )
        repeat(20) { seed ->
            val q = kanjiQuestion(kanjiPool[0], kanjiPool, Random(seed))!!
            assertEquals("うじ", q.correctOption)
            assertEquals(4, q.options.toSet().size)
            assertTrue(q.options.all { o -> o.all { it in '぀'..'ゟ' } }) // all hiragana, like the answer
        }
        // A katakana-only answer gets katakana distractors.
        val onlyOn = listOf(
            kanji("第", "ダイ", ""), kanji("派", "ハ", ""), kanji("貿", "ボウ", ""),
            kanji("易", "エキ", ""), kanji("曜", "ヨウ", ""), kanji("山", "サン", "やま"),
        )
        val q = kanjiQuestion(onlyOn[0], onlyOn, Random(1))!!
        assertTrue(q.options.all { o -> o.all { it in '゠'..'ヿ' } })
    }

    // Placement ------------------------------------------------------------------------------

    private fun PlacementTest.stage(correct: Int, of: Int = 5) = repeat(of) { answer(it < correct) }

    @Test
    fun strongLearnerClimbsToN1() {
        val t = PlacementTest()
        listOf(4, 3, 2, 1).forEach { level ->
            assertEquals(level, t.level)
            t.stage(5)
        }
        assertTrue(t.finished)
        assertEquals(JlptLevel.N1, t.result)
    }

    @Test
    fun stopsWhenTheNextStepWasAlreadyTested() {
        val t = PlacementTest()
        t.stage(5) // N4 pass, up to N3
        assertEquals(3, t.level)
        t.stage(1) // N3 fail, back to N4 which was tested
        assertTrue(t.finished)
        assertEquals(JlptLevel.N4, t.result)
    }

    @Test
    fun weakLearnerDropsToN5ThenBeginner() {
        val t = PlacementTest()
        t.stage(2) // N4 fail, down
        assertEquals(5, t.level)
        t.stage(3) // N5 fail (needs 4), nowhere lower
        assertTrue(t.finished)
        assertEquals(JlptLevel.Beginner, t.result)
    }

    @Test
    fun failingN4ButPassingN5GivesN5() {
        val t = PlacementTest()
        t.stage(3)
        t.stage(4)
        assertTrue(t.finished)
        assertEquals(JlptLevel.N5, t.result)
    }

    @Test
    fun levelDoesNotChangeInTheMiddleOfAStage() {
        val t = PlacementTest()
        repeat(4) { t.answer(true) }
        assertEquals(4, t.level)
        assertEquals(false, t.finished)
    }

    // Results feed the level --------------------------------------------------------------------

    private fun result(type: TestType, level: Int?, correct: Int, total: Int = 20) =
        TestResultEntity(uid = "u", type = type, level = level, correct = correct, total = total, takenAt = 0)

    @Test
    fun testedLevelIsTheHardestPlacementOrPassedCheckpoint() {
        assertNull(testedLevel(emptyList()))
        assertEquals(JlptLevel.N3, testedLevel(listOf(result(TestType.Placement, 3, 5, 5))))
        assertEquals(JlptLevel.Beginner, testedLevel(listOf(result(TestType.Placement, 0, 1, 5))))
        assertEquals(
            JlptLevel.N2,
            testedLevel(listOf(result(TestType.Placement, 4, 5, 5), result(TestType.Checkpoint, 2, 17))),
        )
    }

    @Test
    fun failedCheckpointsAndQuickQuizzesDoNotCount() {
        val failed = result(TestType.Checkpoint, 1, 15) // 75%
        val quick = result(TestType.Quick, null, 10, 10)
        assertNull(testedLevel(listOf(failed, quick)))
        assertNotNull(testedLevel(listOf(result(TestType.Checkpoint, 1, 16)))) // exactly 80%
    }
}
