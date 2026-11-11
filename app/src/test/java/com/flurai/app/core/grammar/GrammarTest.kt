package com.flurai.app.core.grammar

import com.flurai.app.core.content.GrammarExampleEntity
import com.flurai.app.core.content.GrammarPointEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
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
