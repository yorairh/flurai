package com.flurai.app.core.course

import com.flurai.app.core.content.CourseLessonEntity
import com.flurai.app.core.content.CourseQuestionEntity
import com.flurai.app.core.quiz.QuestionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CourseTest {
    private fun lesson(id: Int, position: Int = id, kind: String = KIND_LESSON, related: String = "") =
        CourseLessonEntity(id, position, "$position", kind, if (kind == KIND_NOTE) null else 5, "L$id", "S", null, related, "[]")

    private fun question(lessonId: Int, id: Int, type: String = "choice", options: String = """["a","b","c"]""", answer: Int = 1) =
        CourseQuestionEntity(lessonId, id, type, "Pick one", "prompt", null, options, answer, "because")

    private fun progress(id: Int, passed: Boolean = true, missed: String = "") =
        CourseProgressEntity("u", id, if (passed) 9 else 5, 10, if (passed) 100L else null, 1, missed, 100L)

    // -- status and the next entry ----------------------------------------------------------------------

    @Test
    fun anEntryIsNewTriedOrDone() {
        assertEquals(CourseStatus.New, courseStatus(null))
        assertEquals(CourseStatus.Tried, courseStatus(progress(1, passed = false)))
        assertEquals(CourseStatus.Done, courseStatus(progress(1)))
    }

    @Test
    fun theNextEntryIsTheFirstNotDoneInCourseOrder() {
        val lessons = listOf(lesson(30, 3), lesson(10, 1), lesson(20, 2))
        assertEquals(10, nextCourseEntry(lessons, emptyMap())!!.id)
        assertEquals(20, nextCourseEntry(lessons, mapOf(10 to progress(10)))!!.id)
        // A lesson skipped over stays the suggestion: the course is taken in order.
        assertEquals(10, nextCourseEntry(lessons, mapOf(20 to progress(20), 30 to progress(30)))!!.id)
        assertNull(nextCourseEntry(lessons, lessons.associate { it.id to progress(it.id) }))
    }

    @Test
    fun aNoteIsDoneOnceReadAndNeverCountsAsAPassedLesson() {
        val lessons = listOf(lesson(10), lesson(20, kind = KIND_NOTE))
        val rows = mapOf(10 to progress(10), 20 to noteRead("u", 20, 5L))
        assertEquals(CourseStatus.Done, courseStatus(rows[20]))
        assertEquals(listOf(10), passedLessons(lessons, rows).map { it.id })
    }

    // -- scores ------------------------------------------------------------------------------------------

    private fun answers(lessonId: Int, right: Int, total: Int = 10) =
        (1..total).map { CourseAnswer(lessonId, it, it <= right) }

    @Test
    fun aLessonIsPassedAtEightyPercentAndStaysPassed() {
        val first = null.afterLessonTest("u", 1, answers(1, 7), at = 10)
        assertNull(first.passedAt)
        assertEquals(CourseStatus.Tried, courseStatus(first))

        val second = first.afterLessonTest("u", 1, answers(1, 8), at = 20)
        assertEquals(20L, second.passedAt)

        // A bad later attempt does not undo the pass, and keeps the best score.
        val third = second.afterLessonTest("u", 1, answers(1, 3), at = 30)
        assertEquals(20L, third.passedAt)
        assertEquals(8, third.bestCorrect)
        assertEquals(3, third.attempts)
    }

    @Test
    fun theBestScoreIsKeptAsAShareNotAsACount() {
        val a = null.afterLessonTest("u", 1, answers(1, 3, total = 4), at = 1) // 75%
        val worse = a.afterLessonTest("u", 1, answers(1, 2, total = 10), at = 2) // 20%
        assertEquals(3 to 4, worse.bestCorrect to worse.bestTotal)
        val better = worse.afterLessonTest("u", 1, answers(1, 8, total = 10), at = 3) // 80%
        assertEquals(8 to 10, better.bestCorrect to better.bestTotal)
    }

    @Test
    fun missedQuestionsAreRememberedUntilAnsweredRight() {
        val first = null.afterLessonTest("u", 1, answers(1, 8), at = 1)
        assertEquals(setOf(9, 10), first.missedIds)
        val again = first.afterLessonTest("u", 1, listOf(CourseAnswer(1, 9, true), CourseAnswer(1, 10, false)), at = 2)
        assertEquals(setOf(10), again.missedIds)
    }

    @Test
    fun testYourselfOnlyChangesWhatWasMissed() {
        val base = progress(1, missed = "4")
        val after = base.afterSelfTest(listOf(CourseAnswer(1, 4, true), CourseAnswer(1, 7, false), CourseAnswer(2, 1, false)), at = 500)
        assertEquals(setOf(7), after.missedIds)
        assertEquals(base.bestCorrect, after.bestCorrect)
        assertEquals(base.passedAt, after.passedAt)
        assertEquals(base.attempts, after.attempts)
    }

    // -- building questions ------------------------------------------------------------------------------

    @Test
    fun aChoiceQuestionKeepsItsRightAnswerWhenShuffled() {
        repeat(20) { seed ->
            val q = courseQuestion(question(1, 1, options = """["x","y","z","w"]""", answer = 2), 5, Random(seed))!!
            assertEquals("z", q.options[q.answer])
            assertEquals(QuestionKind.CourseChoice, q.kind)
        }
    }

    @Test
    fun aTwoOptionQuestionKeepsItsOrder() {
        val q = courseQuestion(question(1, 1, options = """["True","False"]""", answer = 0), 5, Random(1))!!
        assertEquals(listOf("True", "False"), q.options)
    }

    @Test
    fun anOrderQuestionIsShownShuffledNeverInTheRightOrder() {
        repeat(20) { seed ->
            val q = courseQuestion(question(1, 1, "order", """["私は","すしを","食べる"]"""), 5, Random(seed))!!
            assertEquals("私はすしを食べる", q.correctText)
            assertNotEquals(listOf("私は", "すしを", "食べる"), q.tiles)
            assertEquals(setOf("私は", "すしを", "食べる"), q.tiles!!.toSet())
        }
    }

    @Test
    fun aTapQuestionKeepsTheWordOrderOfTheSentence() {
        val q = courseQuestion(question(1, 1, "tap", """["さくらが","ボールを","投げた"]""", answer = 0), 5, Random(3))!!
        assertEquals(listOf("さくらが", "ボールを", "投げた"), q.options)
        assertEquals(0, q.answer)
        assertEquals(QuestionKind.CourseTap, q.kind)
    }

    @Test
    fun aBrokenQuestionIsLeftOutInsteadOfBreakingTheTest() {
        assertNull(courseQuestion(question(1, 1, options = "not json"), 5, Random(1)))
        assertNull(courseQuestion(question(1, 1, answer = 7), 5, Random(1)))
        assertNull(courseQuestion(question(1, 1, type = "mystery"), 5, Random(1)))
    }

    // -- the tests ---------------------------------------------------------------------------------------

    private val jlpt: (Int) -> Int = { 5 }

    @Test
    fun aLessonTestAsksTenQuestionsTwoOfThemFromEarlierLessons() {
        val own = (1..12).map { question(2, it) }
        val earlier = (1..12).map { question(1, it) }
        val test = lessonTest(own, earlier, emptySet(), jlpt, Random(7))
        assertEquals(10, test.size)
        assertEquals(2, test.count { it.courseLesson == 1 })
        assertEquals(8, test.count { it.courseLesson == 2 })
    }

    @Test
    fun withNothingEarlierTheTestIsTheLessonsOwn() {
        val test = lessonTest((1..12).map { question(2, it) }, emptyList(), emptySet(), jlpt, Random(7))
        assertEquals(10, test.size)
        assertTrue(test.all { it.courseLesson == 2 })
    }

    @Test
    fun aShortLessonIsFilledUpFromEarlierLessons() {
        val test = lessonTest((1..4).map { question(2, it) }, (1..12).map { question(1, it) }, emptySet(), jlpt, Random(7))
        assertEquals(10, test.size)
        assertEquals(4, test.count { it.courseLesson == 2 })
    }

    @Test
    fun earlierQuestionsMissedBeforeComeFirst() {
        val earlier = (1..12).map { question(1, it) }
        repeat(10) { seed ->
            val test = lessonTest((1..12).map { question(2, it) }, earlier, setOf(1 to 5, 1 to 6), jlpt, Random(seed))
            assertEquals(setOf(5, 6), test.filter { it.courseLesson == 1 }.map { it.courseQuestion }.toSet())
        }
    }

    @Test
    fun testYourselfDrawsAboutHalfFromTheMissedQuestions() {
        val pool = (1..3).flatMap { l -> (1..12).map { question(l, it) } }
        val missed = (1..10).map { 1 to it }.toSet()
        val test = selfTest(pool, missed, 10, jlpt, Random(4))
        assertEquals(10, test.size)
        assertEquals(5, test.count { (it.courseLesson to it.courseQuestion) in missed })
    }

    @Test
    fun testYourselfUsesMissedQuestionsToFillUpWhenThereAreNotEnoughOthers() {
        val pool = (1..5).map { question(1, it) }
        val test = selfTest(pool, pool.map { 1 to it.id }.toSet(), 20, jlpt, Random(4))
        assertEquals(5, test.size) // cannot ask more questions than exist
    }

    @Test
    fun lessonLinksAreReadFromTheRelatedList() {
        assertEquals(listOf(526, 501), lesson(1, related = "526, 501").relatedPoints())
        assertEquals(emptyList<Int>(), lesson(1).relatedPoints())
    }

    @Test
    fun aLessonBodyIsReadFromItsJson() {
        val blocks = parseBody("""[{"type":"text","text":"hi"},{"type":"diagram","cars":[{"text":"さくら","role":"carriage"}]},{"type":"future-thing","extra":1}]""")
        assertEquals(3, blocks.size)
        assertEquals("carriage", blocks[1].cars!!.single().role)
        assertEquals(emptyList<CourseBlock>(), parseBody("not json"))
    }
}
