package com.flurai.app.core.quiz

import com.flurai.app.core.activity.StudyClock
import com.flurai.app.core.model.JlptLevel
import com.flurai.app.core.profile.StudyProfile
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** A whole test from the learner's side: what is on screen after each tap, and what gets saved. */
class QuizRunTest {
    private var time = Instant.parse("2026-03-10T10:00:00Z")
    private val profile = StudyProfile(JlptLevel.N4, 15, 10, true, true)
    private val credited = mutableListOf<Pair<Int, Int>>()

    private class FakeBackend : QuizBackend {
        var questions: List<Question> = emptyList()
        val stagesAsked = mutableListOf<Int>()
        val saved = mutableListOf<TestResultEntity>()
        val grammar = mutableListOf<Triple<Int, Boolean, Long>>()
        val flashcards = mutableListOf<Int>()
        var savedProfile: StudyProfile? = null

        override suspend fun questions(uid: String, kind: QuizKind, profileLevel: JlptLevel, stageLevel: Int): List<Question> {
            if (kind != QuizKind.Placement) return questions
            stagesAsked += stageLevel
            return (0 until 5).map { question(100 * stageLevel + it, level = stageLevel) }
        }

        override suspend fun recordGrammar(uid: String, grammarId: Int, correct: Boolean, at: Long) { grammar += Triple(grammarId, correct, at) }
        override suspend fun saveResult(result: TestResultEntity) { saved += result }
        override suspend fun addWordsToFlashcards(uid: String, wordIds: List<Int>, now: Instant) { flashcards += wordIds }
        override suspend fun saveProfile(uid: String, profile: StudyProfile) { savedProfile = profile }
    }

    private val backend = FakeBackend()

    private fun run(kind: QuizKind) = QuizRun(
        "u", kind, profile, backend,
        StudyClock({ "u" }, { time }) { _, _, seconds, reviews -> credited += seconds to reviews },
        now = { time },
    )

    /** Answers the current question right (index 0 is the answer) or wrong, then moves on. */
    private suspend fun QuizRun.answer(right: Boolean) {
        time = time.plusSeconds(5)
        choose(if (right) 0 else 1)
        next()
    }

    // -- a checkpoint ----------------------------------------------------------------------------------

    @Test
    fun aCheckpointIsPassedAtEightyPercentAndSavedAgainstItsLevel() = runTest {
        backend.questions = (1..10).map { question(it) }
        val run = run(QuizKind.Checkpoint(5))
        run.start()
        assertEquals("N5 checkpoint", run.state.value.title)
        assertEquals(10, run.state.value.total)
        assertEquals(1, run.state.value.number)

        repeat(10) { run.answer(right = it < 8) }

        val result = run.state.value.result!!
        assertEquals(8, result.correct)
        assertEquals(true, result.passed)
        assertNull(run.state.value.question)
        val saved = backend.saved.single()
        assertEquals(TestType.Checkpoint, saved.type)
        assertEquals(5, saved.level)
        assertEquals(8, saved.correct)
        assertEquals(10, saved.total)
    }

    @Test
    fun sevenOutOfTenFailsACheckpoint() = runTest {
        backend.questions = (1..10).map { question(it) }
        val run = run(QuizKind.Checkpoint(3))
        run.start()
        repeat(10) { run.answer(right = it < 7) }

        assertEquals(false, run.state.value.result!!.passed)
    }

    @Test
    fun aQuickQuizHasNoPassMarkAndNoLevel() = runTest {
        backend.questions = (1..3).map { question(it) }
        val run = run(QuizKind.Quick)
        run.start()
        repeat(3) { run.answer(right = true) }

        assertNull(run.state.value.result!!.passed)
        assertNull(backend.saved.single().level)
        assertEquals(TestType.Quick, backend.saved.single().type)
    }

    @Test
    fun theNumberAndProgressFollowTheQuestions() = runTest {
        backend.questions = (1..3).map { question(it) }
        val run = run(QuizKind.Quick)
        run.start()
        assertEquals(1, run.state.value.number)
        run.answer(true)
        assertEquals(2, run.state.value.number)
        assertNull(run.state.value.selected)
    }

    // -- taps ----------------------------------------------------------------------------------------

    @Test
    fun aSecondAnswerToTheSameQuestionIsIgnored() = runTest {
        backend.questions = (1..2).map { question(it) }
        val run = run(QuizKind.Quick)
        run.start()

        run.choose(0)
        run.choose(1)
        run.next()
        run.next() // moves on only once; this one answers nothing yet
        run.choose(0)
        run.next()

        val saved = backend.saved.single()
        assertEquals(2, saved.total)
        assertEquals(2, saved.correct)
    }

    @Test
    fun tappingNextTwiceOnTheLastQuestionSavesOnce() = runTest {
        backend.questions = listOf(question(1))
        val run = run(QuizKind.Quick)
        run.start()
        run.choose(0)

        run.next()
        run.next()

        assertEquals(1, backend.saved.size)
    }

    @Test
    fun nextDoesNothingUntilAnAnswerWasChosen() = runTest {
        backend.questions = (1..2).map { question(it) }
        val run = run(QuizKind.Quick)
        run.start()

        run.next()

        assertEquals(1, run.state.value.number)
        assertTrue(backend.saved.isEmpty())
    }

    @Test
    fun withoutQuestionsTheRunSaysSoInsteadOfLoadingForever() = runTest {
        val run = run(QuizKind.Quick)
        run.start()

        assertFalse(run.state.value.loading)
        assertNotNull(run.state.value.error)
        assertTrue(backend.saved.isEmpty())
    }

    // -- what is done with the answers ---------------------------------------------------------------

    @Test
    fun missedWordsCanBeAddedToTheFlashcardsOnce() = runTest {
        backend.questions = listOf(question(1, wordId = 11), question(2, wordId = 12), question(3, wordId = 11))
        val run = run(QuizKind.Quick)
        run.start()
        run.answer(false)
        run.answer(true)
        run.answer(false)

        assertEquals(listOf(11), run.state.value.result!!.missedWordIds) // listed once even if missed twice
        run.addMissedToFlashcards()
        assertEquals(listOf(11), backend.flashcards)
        assertTrue(run.state.value.result!!.missedAdded)
    }

    @Test
    fun aGrammarAnswerIsRecordedAgainstItsPoint() = runTest {
        backend.questions = listOf(question(1, grammarId = 501), question(2, grammarId = 502))
        val run = run(QuizKind.Grammar(5))
        run.start()
        run.answer(true)
        run.answer(false)

        assertEquals(listOf(501 to true, 502 to false), backend.grammar.map { it.first to it.second })
        assertEquals(TestType.Grammar, backend.saved.single().type)
        assertEquals(5, backend.saved.single().level)
    }

    @Test
    fun everyAnswerCountsAsAReviewAndTheFinishStopsTheClock() = runTest {
        backend.questions = (1..3).map { question(it) }
        val run = run(QuizKind.Quick)
        run.start()
        repeat(3) { run.answer(true) }

        assertEquals(3, credited.sumOf { it.second })
        assertTrue(credited.sumOf { it.first } >= 15)
        val before = credited.size
        time = time.plusSeconds(600)
        run.resume() // finished: the clock stays stopped
        run.pause()
        assertEquals(before, credited.size)
    }

    // -- the placement test ----------------------------------------------------------------------------

    @Test
    fun answeringEverythingRightClimbsFromN4ToN1() = runTest {
        val run = run(QuizKind.Placement)
        run.start()
        assertNull(run.state.value.total) // the length is not known in advance

        repeat(20) { run.answer(true) }

        assertEquals(listOf(4, 3, 2, 1), backend.stagesAsked)
        assertEquals(JlptLevel.N1, run.state.value.result!!.placementLevel)
        assertEquals(1, backend.saved.single().level)
        assertEquals(TestType.Placement, backend.saved.single().type)
    }

    @Test
    fun answeringEverythingWrongEndsAtBeginner() = runTest {
        val run = run(QuizKind.Placement)
        run.start()

        repeat(10) { run.answer(false) }

        assertEquals(listOf(4, 5), backend.stagesAsked)
        assertEquals(JlptLevel.Beginner, run.state.value.result!!.placementLevel)
        assertEquals(0, backend.saved.single().level)
    }

    @Test
    fun passingN4ButFailingN3PlacesTheLearnerAtN4() = runTest {
        val run = run(QuizKind.Placement)
        run.start()

        repeat(5) { run.answer(true) }
        assertFalse(run.state.value.loading)
        assertEquals(3, run.state.value.question!!.level)
        repeat(5) { run.answer(false) }

        assertEquals(JlptLevel.N4, run.state.value.result!!.placementLevel)
        assertEquals(listOf(4, 3), backend.stagesAsked)
    }

    @Test
    fun thePlacementLevelCanBeAdoptedAsTheStudyLevel() = runTest {
        val run = run(QuizKind.Placement)
        run.start()
        repeat(10) { run.answer(it < 5) }

        run.applyPlacementLevel()

        assertEquals(JlptLevel.N4, backend.savedProfile!!.level)
        assertTrue(run.state.value.result!!.levelApplied)
    }

    // -- the kinds themselves ----------------------------------------------------------------------------

    @Test
    fun everyKindSurvivesTheRouteRoundTrip() {
        val kinds = listOf(
            QuizKind.Placement, QuizKind.Quick, QuizKind.Listening, QuizKind.Checkpoint(3),
            QuizKind.Grammar(4), QuizKind.Grammar(2, 777), QuizKind.Reading(1),
        )
        kinds.forEach { assertEquals(it, QuizKind.decode(it.encode())) }
    }

    @Test
    fun anUnknownRouteTextFallsBackToAQuickQuiz() {
        assertEquals(QuizKind.Quick, QuizKind.decode("nonsense"))
    }

    @Test
    fun eachKindKnowsItsTitleTypeAndLevel() {
        assertEquals("Placement test", QuizKind.Placement.title)
        assertEquals("N3 checkpoint", QuizKind.Checkpoint(3).title)
        assertEquals("N5 grammar", QuizKind.Grammar(5, 12).title)
        assertEquals("N2 reading", QuizKind.Reading(2).title)
        assertEquals(TestType.Listening, QuizKind.Listening.testType)
        assertEquals(TestType.Reading, QuizKind.Reading(2).testType)
        assertEquals(2, QuizKind.Reading(2).savedLevel(null))
        assertNull(QuizKind.Listening.savedLevel(null))
        assertEquals(0, QuizKind.Placement.savedLevel(null))
        assertEquals(3, QuizKind.Placement.savedLevel(JlptLevel.N3))
        assertTrue(QuizKind.Placement.adaptive)
        assertFalse(QuizKind.Quick.adaptive)
    }
}

private fun question(n: Int, level: Int = 5, wordId: Int? = null, grammarId: Int? = null) = Question(
    kind = QuestionKind.WordMeaning, prompt = "q$n", hint = null, options = listOf("right", "wrong", "x", "y"), answer = 0,
    level = level, wordId = wordId, grammarId = grammarId,
)
