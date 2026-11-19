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
    fun aLessonTestAsksTenQuestionsAllFromTheLesson() {
        val test = lessonTest((1..12).map { question(2, it) }, jlpt, Random(7))
        assertEquals(10, test.size)
        assertTrue(test.all { it.courseLesson == 2 })
    }

    @Test
    fun noMoreThanTwoQuestionsOfALessonTestHaveTheSameRightAnswer() {
        // 6 gaps that all want を and 8 that each want another particle: a test of ten takes two of the を ones
        val wo = (1..6).map { question(2, it, options = """["を","x","y"]""", answer = 0) }
        val others = listOf("に", "で", "が", "と", "へ", "も", "の", "か").mapIndexed { i, a -> question(2, 10 + i, options = """["$a","x","y"]""", answer = 0) }
        repeat(20) { seed ->
            val test = lessonTest(wo + others, jlpt, Random(seed))
            assertEquals(10, test.size)
            assertTrue(test.count { it.options[it.answer] == "を" } <= SAME_ANSWER_LIMIT)
        }
    }

    @Test
    fun aLessonWithOnlyOneRightAnswerStillGetsAFullTest() {
        val test = lessonTest((1..12).map { question(2, it, answer = 0) }, jlpt, Random(3))
        assertEquals(10, test.size)
    }

    @Test
    fun aShortLessonAsksAllItsQuestions() {
        assertEquals(4, lessonTest((1..4).map { question(2, it) }, jlpt, Random(7)).size)
    }

    // -- checkups ----------------------------------------------------------------------------------------

    private fun lessons(count: Int) = (1..count).map { lesson(it * 10, it) }

    @Test
    fun aCheckupFollowsEveryFiveLessonsAndNotesDoNotCount() {
        val all = lessons(12) + lesson(55, 3, kind = KIND_NOTE)
        val groups = checkupGroups(all)
        assertEquals(2, groups.size) // 12 lessons: two complete groups, the last two wait
        assertEquals(listOf(10, 20, 30, 40, 50), groups[0].map { it.id })
        assertEquals(100, checkupId(groups[1]))
        assertTrue(checkupGroups(lessons(4)).isEmpty())
    }

    @Test
    fun aCheckupOpensWhenAllFiveLessonsArePassed() {
        val group = checkupGroups(lessons(5))[0]
        val rows = group.associate { it.id to progress(it.id) }
        assertTrue(checkupUnlocked(group, rows))
        assertTrue(!checkupUnlocked(group, rows - 30))
        assertTrue(!checkupUnlocked(group, rows + (30 to progress(30, passed = false))))
    }

    @Test
    fun aCheckupAsksTwentyQuestionsFourFromEachLesson() {
        val byLesson = (1..5).map { l -> (1..12).map { question(l, it) } }
        val test = checkupTest(byLesson, emptySet(), jlpt, Random(5))
        assertEquals(20, test.size)
        (1..5).forEach { l -> assertEquals(4, test.count { it.courseLesson == l }) }
    }

    @Test
    fun aCheckupAsksTheMissedQuestionsOfALessonFirst() {
        val byLesson = (1..5).map { l -> (1..12).map { question(l, it) } }
        repeat(10) { seed ->
            val test = checkupTest(byLesson, setOf(3 to 2, 3 to 9), jlpt, Random(seed))
            assertTrue(test.filter { it.courseLesson == 3 }.map { it.courseQuestion }.containsAll(listOf(2, 9)))
        }
    }

    @Test
    fun aCheckupIsFilledUpFromTheOtherLessonsWhenOneHasTooFew() {
        val byLesson = listOf((1..2).map { question(1, it) }) + (2..5).map { l -> (1..12).map { question(l, it) } }
        val test = checkupTest(byLesson, emptySet(), jlpt, Random(5))
        assertEquals(20, test.size)
        assertEquals(2, test.count { it.courseLesson == 1 })
    }

    @Test
    fun aCheckupScoreIsKeptLikeALessonsBestAndPassesAtEightyPercent() {
        val first = null.afterCheckup("u", 100_050, 15, 20, at = 1) // 75%
        assertNull(first.passedAt)
        val second = first.afterCheckup("u", 100_050, 17, 20, at = 2) // 85%
        assertEquals(2L, second.passedAt)
        val third = second.afterCheckup("u", 100_050, 10, 20, at = 3)
        assertEquals(17, third.bestCorrect)
        assertEquals(2L, third.passedAt)
        assertEquals(3, third.attempts)
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

    // -- furigana -----------------------------------------------------------------------------------------

    @Test
    fun aQuestionCarriesTheFuriganaOfItsTextsByPlainText() {
        val entity = CourseQuestionEntity(
            10, 1, "choice", "Pick the verb", "犬が走る。", null, """["走る","犬"]""", 0, "走る is a verb",
            ruby = """{"prompt": "{犬|いぬ}が{走|はし}る。", "options": ["{走|はし}る", "{犬|いぬ}"], "note": "{走|はし}る is a verb"}""",
        )
        val q = courseQuestion(entity, 5, Random(1))!!
        assertEquals("{犬|いぬ}が{走|はし}る。", q.ruby["犬が走る。"])
        assertEquals("{走|はし}る", q.ruby["走る"])
        assertEquals("{走|はし}る is a verb", q.ruby["走る is a verb"])
        assertNull(q.ruby["Pick the verb"])
    }

    @Test
    fun theRightArrangementOfAnOrderQuestionHasTheJoinedFurigana() {
        val entity = CourseQuestionEntity(
            10, 2, "order", "Build it", "", null, """["私が","電気を","消した"]""", 0, "x",
            ruby = """{"options": ["{私|わたし}が", "{電気|でんき}を", "{消|け}した"]}""",
        )
        val q = courseQuestion(entity, 5, Random(1))!!
        assertEquals("{私|わたし}が{電気|でんき}を{消|け}した", q.ruby["私が電気を消した"])
    }

    @Test
    fun brokenOrMissingFuriganaJustMeansNone() {
        assertTrue(rubyMap(null, mapOf("a" to "x")).isEmpty())
        assertTrue(rubyMap("not json", mapOf("a" to "x")).isEmpty())
        assertTrue(rubyMap("""{"a": 5}""", mapOf("a" to "x")).isEmpty())
    }

    @Test
    fun anOrderQuestionAcceptsItsAlternativeArrangements() {
        val entity = CourseQuestionEntity(
            10, 3, "order", "Build it", "A dog runs.", null, """["犬が","庭で","走る"]""", 0, "x",
            alt = """[["庭で","犬が","走る"]]""",
        )
        val q = courseQuestion(entity, 5, Random(1))!!
        assertTrue(q.isArrangementRight("犬が庭で走る"))
        assertTrue(q.isArrangementRight("庭で犬が走る"))
        assertTrue(!q.isArrangementRight("走る犬が庭で"))
    }

    @Test
    fun nextIsTheEntryAfterThisOneNotTheFirstUnpassed() {
        val lessons = listOf(lesson(10, 1), lesson(20, 2), lesson(30, 3))
        assertEquals(20, entryAfter(lessons, 10)!!.id)
        assertEquals(30, entryAfter(lessons, 20)!!.id)
        assertNull(entryAfter(lessons, 30))
        assertNull(entryAfter(lessons, 999))
    }

    @Test
    fun aTableFillsTheWidthWhenItFitsAndScrollsWhenItDoesNot() {
        val small = listOf(listOf("A", "B"), listOf("a", "b"))
        val fits = tableLayout(small, 320f)
        assertEquals(false, fits.scrolls)
        assertEquals(320f, fits.widths.sum(), 0.01f)
        val wide = listOf(List(5) { "これは長いことばです" }, List(5) { "これは長いことばです" })
        val scrolls = tableLayout(wide, 320f)
        assertTrue(scrolls.scrolls)
        assertTrue(scrolls.widths.sum() > 320f)
    }

    @Test
    fun columnsAreSizedToTheirContent() {
        val rows = listOf(listOf("i", "a long explanation of the thing"), listOf("o", "another longish explanation"))
        val layout = tableLayout(rows, 360f)
        assertTrue(layout.widths[1] > layout.widths[0])
    }

    @Test
    fun anArrowIsCountedAsAFullWidthCharacter() {
        val layout = tableLayout(listOf(listOf("→→→→→→", "あ"), listOf("→→", "い")), 360f)
        assertTrue(layout.widths[0] > 200f)
    }

    @Test
    fun aDialogueBlockIsParsed() {
        val body = """[{"type":"dialogue","lines":[{"who":"A","jp":"こんにちは。","en":"Hello."},{"who":"B","jp":"やあ。","en":"Hi."}]}]"""
        val block = parseBody(body).single()
        assertEquals("dialogue", block.type)
        assertEquals(listOf("A", "B"), block.lines!!.map { it.who })
    }
}
