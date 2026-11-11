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
    fun commasInsideBracketsDoNotSplitAGloss() {
        assertEquals("cold (things, drinks)", firstGloss("cold (things, drinks), chilly"))
        assertEquals(listOf("to run", "to flow"), splitGlosses("to run; to flow"))
        assertEquals(listOf("a (b; c)", "d"), splitGlosses("a (b; c), d"))
    }

    @Test
    fun aLongGlossIsCutAtAWordNeverInsideAWordOrABracket() {
        val long = firstGloss("counter for stories (floors) of a building, extra")
        assertEquals("counter for stories (floors) of a", long)
        val open = firstGloss("a very long explanation of something (with a bracket that is far too long to fit)")
        assertTrue(open.length <= 40)
        assertTrue("(" !in open || ")" in open)
        assertTrue(!open.endsWith("(wit"))
    }

    @Test
    fun wrongOptionsAreTheSameKindOfWordAtTheSameLevelFirst() {
        val verbs = (1..6).map { word(it, "動$it", "move $it", pos = "v5r", jlpt = 5) }
        val nouns = (10..16).map { word(it, "名$it", "thing $it", pos = "n", jlpt = 5) }
        val harderVerbs = (20..24).map { word(it, "難$it", "hard $it", pos = "v1", jlpt = 2) }
        val all = verbs + nouns + harderVerbs
        repeat(20) { seed ->
            val q = wordQuestion(verbs[0], all, Random(seed))!!
            val wrong = q.options.filter { it != q.correctOption }
            // five other N5 verbs are available, so no noun and no harder verb is needed
            assertTrue("seed $seed: $wrong", wrong.all { it.startsWith("move") })
        }
    }

    @Test
    fun adjectivesAndAdverbsAreNotMixedUp() {
        val adj = word(1, "高い", "tall", pos = "adj-i")
        val adv = word(2, "とても", "very", pos = "adv")
        assertEquals("i-adjective", family(adj))
        assertEquals("adverb", family(adv))
        assertEquals("verb", family(word(3, "行く", "go", pos = "v5k")))
        assertEquals("noun", family(word(4, "水", "water", pos = "n")))
    }

    @Test
    fun readingQuestionAsksForTheWholeWordsReading() {
        val words = listOf(
            word(1, "学校", "school").copy(reading = "がっこう"),
            word(2, "先生", "teacher").copy(reading = "せんせい"),
            word(3, "会社", "company").copy(reading = "かいしゃ"),
            word(4, "電話", "telephone").copy(reading = "でんわ"),
            word(5, "水", "water").copy(reading = "みず"),
            word(6, "ありがとう", "thanks"),
        )
        repeat(20) { seed ->
            val q = wordReadingQuestion(words[0], words, Random(seed))!!
            assertEquals(QuestionKind.KanjiReading, q.kind)
            assertEquals("学校", q.prompt)
            assertEquals("がっこう", q.correctOption)
            assertEquals(4, q.options.toSet().size)
            assertEquals(1, q.wordId) // a missed reading can be added to the flashcards
            assertTrue("ありがとう" !in q.options) // a kana word has no kanji reading to mix up
        }
        assertNull(wordReadingQuestion(words[5], words, Random(1))) // no kanji: nothing to ask
    }

    // Placement ------------------------------------------------------------------------------

    private fun PlacementTest.stage(correct: Int, of: Int = 6) = repeat(of) { answer(it < correct) }

    @Test
    fun placementStartsAtN5AndClimbsWhileEachLevelIsPassed() {
        val t = PlacementTest()
        listOf(5, 4, 3, 2, 1).forEach { level ->
            assertEquals(level, t.level)
            assertEquals(false, t.finished)
            t.stage(5) // 5 of 6 passes
        }
        assertTrue(t.finished)
        assertEquals(JlptLevel.N1, t.result)
    }

    @Test
    fun placementStopsAtTheFirstLevelNotPassed() {
        val t = PlacementTest()
        t.stage(6) // N5
        t.stage(6) // N4
        t.stage(4) // N3: one short
        assertTrue(t.finished)
        assertEquals(JlptLevel.N4, t.result)
    }

    @Test
    fun failingN5GivesBeginner() {
        val t = PlacementTest()
        t.stage(4)
        assertTrue(t.finished)
        assertEquals(JlptLevel.Beginner, t.result)
    }

    @Test
    fun levelDoesNotChangeInTheMiddleOfAStage() {
        val t = PlacementTest()
        repeat(5) { t.answer(true) }
        assertEquals(5, t.level)
        assertEquals(false, t.finished)
    }

    // Results feed the level --------------------------------------------------------------------

    private fun result(type: TestType, level: Int?, correct: Int, total: Int = 20) =
        TestResultEntity(uid = "u", type = type, level = level, correct = correct, total = total, takenAt = 0)

    @Test
    fun onlyPassedCheckpointsCountAsEvidenceOfALevel() {
        assertEquals(emptySet<Int>(), passedCheckpoints(emptyList()))
        assertEquals(emptySet<Int>(), passedCheckpoints(listOf(result(TestType.Placement, 3, 5, 5)))) // a placement is a suggestion
        assertEquals(setOf(2, 5), passedCheckpoints(listOf(result(TestType.Checkpoint, 2, 17), result(TestType.Checkpoint, 5, 20), result(TestType.Placement, 1, 6, 6))))
    }

    @Test
    fun failedCheckpointsAndQuickQuizzesDoNotCount() {
        val failed = result(TestType.Checkpoint, 1, 15) // 75%
        val quick = result(TestType.Quick, null, 10, 10)
        assertEquals(emptySet<Int>(), passedCheckpoints(listOf(failed, quick)))
        assertEquals(setOf(1), passedCheckpoints(listOf(result(TestType.Checkpoint, 1, 16)))) // exactly 80%
    }

    @Test
    fun theLatestPlacementIsTheSuggestedLevel() {
        assertNull(suggestedLevel(emptyList()))
        val older = result(TestType.Placement, 3, 5, 6).copy(takenAt = 1)
        val newer = result(TestType.Placement, 0, 1, 6).copy(takenAt = 2)
        assertEquals(JlptLevel.Beginner, suggestedLevel(listOf(older, newer)))
        assertEquals(JlptLevel.N3, suggestedLevel(listOf(older)))
    }
}
