package com.flurai.app.core.grammar

import com.flurai.app.core.content.GrammarExampleEntity
import com.flurai.app.core.content.GrammarPointEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random

class GrammarTest {
    private fun point(id: Int) = GrammarPointEntity(id, 5, "p$id", "t", "m", "f", "e")
    private fun example(id: Int, jp: String, answer: String) = GrammarExampleEntity(id, 0, jp, "en", answer)

    private val pool = listOf(
        example(1, "私は学生です。", "は"),
        example(2, "誰が来ましたか。", "が"),
        example(3, "水を飲みます。", "を"),
        example(4, "七時に起きます。", "に"),
        example(5, "図書館で読みます。", "で"),
    )

    @Test
    fun statusFollowsAnswers() {
        assertEquals(GrammarStatus.New, grammarStatus(null))
        var p = null as GrammarProgressEntity?
        p = p.afterAnswer("u", 1, true, 0)
        assertEquals(GrammarStatus.Learning, grammarStatus(p))
        p = p.afterAnswer("u", 1, true, 1).afterAnswer("u", 1, true, 2)
        assertEquals(GrammarStatus.Known, grammarStatus(p))
        p = p.afterAnswer("u", 1, false, 3) // slipping up makes it Learning again
        assertEquals(GrammarStatus.Learning, grammarStatus(p))
        assertEquals(3, p.correct)
        assertEquals(1, p.wrong)
    }

    @Test
    fun markingALessonLearnedStartsThePointWithoutAnyAnswer() {
        val started = startedGrammar("u", 1, 5)
        assertEquals(GrammarStatus.Learning, grammarStatus(started))
        assertEquals(0, started.correct + started.wrong)
        // Practice then counts on top of it.
        val known = started.afterAnswer("u", 1, true, 6).afterAnswer("u", 1, true, 7).afterAnswer("u", 1, true, 8)
        assertEquals(GrammarStatus.Known, grammarStatus(known))
    }

    @Test
    fun n5LessonsStartWithDesuAndCoverEveryPointOnce() {
        val file = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .map { java.io.File(it, "tools/content/grammar.json") }.first { it.exists() }
        val n5 = kotlinx.serialization.json.Json.parseToJsonElement(file.readText()).jsonArray
            .map { it.jsonObject }.filter { it["jlpt"]!!.jsonPrimitive.int == 5 }.map { it["id"]!!.jsonPrimitive.int }
        assertEquals(n5.sorted(), N5_LESSON_ORDER.sorted())
        assertEquals(526, N5_LESSON_ORDER.first()) // です before the particles
    }

    @Test
    fun lessonsGoEasiestLevelFirstAndUnlistedPointsById() {
        fun p(id: Int, jlpt: Int) = GrammarPointEntity(id, jlpt, "p$id", "t", "m", "f", "e")
        val points = listOf(p(402, 4), p(501, 5), p(401, 4), p(526, 5), p(590, 5))
        assertEquals(listOf(526, 501, 590, 401, 402), lessonOrder(points).map { it.id })
        assertEquals(526, nextLesson(points, emptySet(), 5)?.id)
        assertEquals(590, nextLesson(points, setOf(526, 501), 5)?.id)
        assertEquals(401, nextLesson(points, setOf(526), 4)?.id)
        assertNull(nextLesson(points, setOf(526, 501, 590), 5))
    }

    @Test
    fun practiceAsksOnlyAboutLearnedPoints() {
        val examples = (1..6).flatMap { id -> listOf(example(id, "a$id", "x"), example(id, "b$id", "y")) }
        val status = mapOf(1 to GrammarStatus.Known, 2 to GrammarStatus.Learning, 3 to GrammarStatus.Learning)
        repeat(10) { seed ->
            val chosen = practiceExamples(examples, status, learnedOnly = true, favorUnknown = true, random = Random(seed))
            assertEquals(setOf(1, 2, 3), chosen.map { it.grammarId }.toSet())
            // One sentence per point first, the points still being learned before the known one; then the other sentences.
            assertEquals(setOf(2, 3), chosen.take(2).map { it.grammarId }.toSet())
            assertEquals(1, chosen[2].grammarId)
            assertEquals(6, chosen.size)
        }
        assertTrue(practiceExamples(examples, emptyMap(), learnedOnly = true, favorUnknown = true, random = Random(1)).isEmpty())
    }

    @Test
    fun aTestCoversTheWholeLevel() {
        val examples = (1..6).map { id -> example(id, "a$id", "x") }
        val chosen = practiceExamples(examples, mapOf(1 to GrammarStatus.Learning), learnedOnly = false, favorUnknown = false, random = Random(1))
        assertEquals((1..6).toSet(), chosen.map { it.grammarId }.toSet())
    }

    @Test
    fun blankQuestionHidesTheAnswerAndOffersFourDistinctOptions() {
        repeat(20) { seed ->
            val q = grammarQuestion(point(1), pool[0], pool, Random(seed))!!
            assertEquals("私＿＿学生です。", q.prompt)
            assertEquals("は", q.correctOption)
            assertEquals(4, q.options.toSet().size)
            assertEquals(1, q.grammarId)
            assertEquals("en", q.hint)
        }
    }

    @Test
    fun overlappingPartsAreNotUsedAsDistractors() {
        val pool = listOf(
            example(1, "行ったことがある。", "たことが"),
            example(2, "行くことができる。", "ことが"), // sits inside the answer
            example(3, "歩きながら", "ながら"),
            example(4, "見たり", "たり"),
            example(5, "高くても", "ても"),
            example(6, "安ければ", "れば"),
        )
        val q = grammarQuestion(point(1), pool[0], pool, Random(1))!!
        assertTrue("ことが" !in q.options)
    }

    @Test
    fun aParticleGetsParticlesAsWrongOptions() {
        val pool = pool + listOf(
            example(6, "駅まで歩く。", "まで"), example(7, "友達と行く。", "と"),
            example(8, "行ったことがある。", "たことが"), example(9, "食べてください。", "てください"),
        )
        repeat(20) { seed ->
            val q = grammarQuestion(point(1), pool[0], pool, Random(seed))!!
            assertTrue("seed $seed: ${q.options}", q.options.all { it.length <= 2 }) // は among がをにでまでと, never たことが
        }
    }

    @Test
    fun answerShapeGroupsLikeWithLike() {
        assertEquals("short", answerShape("は"))
        assertEquals("medium", answerShape("てください"))
        assertEquals("kanji-medium", answerShape("予定だ"))
        assertEquals("long", answerShape("なければならない"))
    }

    @Test
    fun theNoteGivesTheFirstSentenceOfTheExplanation() {
        assertEquals("が marks the subject.", firstSentence("が marks the subject. It is also used with 好き."))
        assertEquals("ここでは句点がない", firstSentence("ここでは句点がない"))
        val q = grammarQuestion(GrammarPointEntity(1, 5, "が", "t", "subject", "f", "が marks the subject. More."), pool[1], pool, Random(1))!!
        assertEquals("が: subject" + "\n" + "が marks the subject.", q.note)
    }

    @Test
    fun needsEnoughOtherPoints() {
        assertNull(grammarQuestion(point(1), pool[0], pool.take(3), Random(1)))
        assertNotNull(grammarQuestion(point(1), pool[0], pool, Random(1)))
    }
}
