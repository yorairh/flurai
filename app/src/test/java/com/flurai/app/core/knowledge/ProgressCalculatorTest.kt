package com.flurai.app.core.knowledge

import com.flurai.app.core.content.WordEntity
import com.flurai.app.core.grammar.GrammarProgressEntity
import com.flurai.app.core.model.JlptLevel
import com.flurai.app.core.srs.SrsState
import com.flurai.app.core.study.CardEntity
import com.flurai.app.core.study.CardKind
import com.flurai.app.core.study.ItemType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class ProgressCalculatorTest {
    private val now = Instant.parse("2026-03-10T03:00:00Z")
    private val tokyo = ZoneId.of("Asia/Tokyo")

    private fun card(
        id: Int,
        kind: CardKind = CardKind.Recognition,
        type: ItemType = ItemType.Word,
        state: SrsState = SrsState.Review,
        stability: Double? = 10.0,
        reps: Int = 3,
        lapses: Int = 0,
        suspended: Boolean = false,
        due: Instant = now,
    ) = CardEntity(
        uid = "u", itemType = type, itemId = id, kind = kind, state = state.value,
        step = if (state == SrsState.Review) null else 0, stability = stability, difficulty = 5.0,
        due = due.toEpochMilli(), lastReview = if (reps > 0) now.minus(Duration.ofDays(1)).toEpochMilli() else null,
        reps = reps, lapses = lapses, suspended = suspended, buriedUntil = null, createdAt = 0,
    )

    private fun word(id: Int, jlpt: Int, expression: String) = WordEntity(
        id = id, expression = expression, reading = expression, otherForms = null, meanings = "m",
        jlpt = jlpt, genkiLesson = null, jmdictId = null, partsOfSpeech = null,
    )

    private val catalog = Catalog(
        words = listOf(word(1, 5, "食べる"), word(2, 5, "水"), word(3, 4, "学校")).associateBy { it.id },
        kanjiLevels = mapOf("食" to 5, "水" to 5, "飲" to 5, "学" to 4, "校" to 4),
        grammarLevels = mapOf(1 to 5, 2 to 5, 3 to 4),
    )

    private fun progress(cards: List<CardEntity> = emptyList(), grammar: List<GrammarProgressEntity> = emptyList(), checkpoints: Set<Int> = emptySet()) =
        computeProgress(cards, catalog, grammar, checkpoints, now, tokyo)

    private fun grammarAnswers(id: Int, correct: Int, wrong: Int = 0, lastCorrect: Boolean = true) =
        GrammarProgressEntity("u", id, correct, wrong, lastCorrect, 0)

    private fun evidence(jlpt: Int, words: Double = 0.0, grammar: Double = 0.0, checkpoint: Boolean = false) =
        jlpt to LevelEvidence(jlpt, words, grammar, checkpoint)

    @Test
    fun knowledgeFollowsStabilityAndState() {
        assertEquals(Knowledge.Unseen, card(1, reps = 0, stability = null, state = SrsState.Learning).knowledge())
        assertEquals(Knowledge.Learning, card(1, state = SrsState.Learning, stability = 2.3).knowledge())
        assertEquals(Knowledge.Learning, card(1, state = SrsState.Relearning, stability = 30.0).knowledge())
        assertEquals(Knowledge.Learning, card(1, stability = 6.9).knowledge())
        assertEquals(Knowledge.Known, card(1, stability = 7.0).knowledge())
        assertEquals(Knowledge.Known, card(1, stability = 20.9).knowledge())
        assertEquals(Knowledge.Mature, card(1, stability = 21.0).knowledge())
    }

    @Test
    fun withNothingKnownTheLevelIsBeginnerAndTheGoalIsN5() {
        val estimate = estimateLevel(emptyMap())
        assertEquals(JlptLevel.Beginner, estimate.current)
        assertEquals(JlptLevel.N5, estimate.goal)
        assertEquals(0f, estimate.goalProgress, 0f)
    }

    @Test
    fun aLevelNeedsBothWordsAndGrammarFromStudy() {
        // Many words but little grammar: not reached, and the weaker of the two is the progress.
        val wordsOnly = estimateLevel(mapOf(evidence(5, words = 0.95, grammar = 0.4)))
        assertEquals(JlptLevel.Beginner, wordsOnly.current)
        assertEquals(0.5f, wordsOnly.goalProgress, 1e-6f)

        val both = estimateLevel(mapOf(evidence(5, words = 0.85, grammar = 0.8)))
        assertEquals(JlptLevel.N5, both.current)
        assertEquals(JlptLevel.N4, both.goal)
    }

    @Test
    fun aPassedCheckpointIsEnoughOnItsOwn() {
        val estimate = estimateLevel(mapOf(evidence(5, checkpoint = true)))
        assertEquals(JlptLevel.N5, estimate.current)
    }

    @Test
    fun aPassedHarderCheckpointCoversTheEasierLevels() {
        val estimate = estimateLevel(mapOf(evidence(3, checkpoint = true)))
        assertEquals(JlptLevel.N3, estimate.current)
        assertEquals(JlptLevel.N2, estimate.goal)
    }

    @Test
    fun studyingAHarderLevelDoesNotSkipAnEasierOneThatIsNotKnown() {
        val estimate = estimateLevel(mapOf(evidence(5, words = 0.5, grammar = 0.5), evidence(4, words = 0.95, grammar = 0.95)))
        assertEquals(JlptLevel.Beginner, estimate.current)
    }

    @Test
    fun topLevelHasNoGoal() {
        val estimate = estimateLevel(mapOf(evidence(1, checkpoint = true)))
        assertEquals(JlptLevel.N1, estimate.current)
        assertNull(estimate.goal)
        assertEquals(1f, estimate.goalProgress, 0f)
    }

    @Test
    fun theLevelChosenAtSignUpIsNotEvidence() {
        // computeProgress no longer takes the study level: with no cards, grammar or checkpoints the level is Beginner.
        assertEquals(JlptLevel.Beginner, progress().estimate.current)
    }

    @Test
    fun coverageCountsRecognitionCardsByState() {
        val cards = listOf(
            card(1, stability = 30.0),
            card(1, kind = CardKind.Production, stability = 1.0), // not counted: only recognition decides
            card(2, state = SrsState.Learning, stability = 2.3),
            card(3, stability = 10.0),
            card(9, type = ItemType.Kana, stability = 50.0), // kana never count as words
        )
        val snapshot = progress(cards)

        val (n5, n4) = snapshot.vocab.let { it[0] to it[1] }
        assertEquals(listOf(5, 4, 3, 2, 1), snapshot.vocab.map { it.jlpt })
        assertEquals(listOf(2, 1, 1, 0), listOf(n5.total, n5.mature, n5.learning, n5.known))
        assertEquals(listOf(1, 1, 0), listOf(n4.total, n4.known, n4.mature))
        assertEquals(3, snapshot.studiedWords)
        assertEquals(2, snapshot.knownWords)

        // 1 of 2 N5 words known and no grammar: still Beginner with N5 as the goal.
        assertEquals(JlptLevel.Beginner, snapshot.estimate.current)
        assertEquals(JlptLevel.N5, snapshot.estimate.goal)
        assertEquals(0.5, snapshot.estimate.goalWords, 1e-9)
        assertEquals(0.0, snapshot.estimate.goalGrammar, 1e-9)
    }

    @Test
    fun grammarCountsPointsKnownPerLevel() {
        val grammar = listOf(
            grammarAnswers(1, correct = 3), // known
            grammarAnswers(2, correct = 3, wrong = 1, lastCorrect = false), // latest answer wrong: still learning
            grammarAnswers(3, correct = 5), // an N4 point
            grammarAnswers(99, correct = 9), // a point that no longer exists is ignored
        )
        val vocab = progress(grammar = grammar).vocab
        assertEquals(listOf(2, 1), listOf(vocab[0].grammarTotal, vocab[0].grammarKnown)) // N5
        assertEquals(listOf(1, 1), listOf(vocab[1].grammarTotal, vocab[1].grammarKnown)) // N4
        assertEquals(0.5, vocab[0].grammarFraction, 1e-9)
    }

    @Test
    fun levelRisesWithWordsAndGrammarOrWithAPassedCheckpoint() {
        // Both N5 words known and both N5 grammar points known: N5 reached by study.
        val cards = listOf(card(1, stability = 30.0), card(2, stability = 30.0))
        val grammar = listOf(grammarAnswers(1, 3), grammarAnswers(2, 4))
        assertEquals(JlptLevel.N5, progress(cards, grammar).estimate.current)
        assertEquals(JlptLevel.Beginner, progress(cards).estimate.current) // words alone are not enough
        assertEquals(JlptLevel.N4, progress(checkpoints = setOf(4)).estimate.current)
        assertTrue(progress(checkpoints = setOf(4)).vocab.first { it.jlpt == 4 }.checkpointPassed)
    }

    @Test
    fun kanjiCountWhenTheyAppearInAKnownWord() {
        val cards = listOf(
            card(1, stability = 30.0), // 食べる: known, so 食 counts
            card(2, state = SrsState.Learning, stability = 2.0), // 水: still learning, doesn't count
            card(3, stability = 10.0), // 学校: known
        )
        val kanji = progress(cards).kanji
        assertEquals(KanjiProgress(5, total = 3, known = 1), kanji[0])
        assertEquals(KanjiProgress(4, total = 2, known = 2), kanji[1])
        assertEquals(KanjiProgress(1, total = 0, known = 0), kanji[4])
    }

    @Test
    fun weakWordsAreTheOnesForgottenMostAndNeverTheSuspendedOrKana() {
        val cards = listOf(
            card(1, lapses = 2),
            card(1, kind = CardKind.Production, lapses = 1), // lapses add up across both cards
            card(2, lapses = 1),
            card(3, lapses = 0), // never forgotten: not weak
            card(3, kind = CardKind.Production, lapses = 5, suspended = true),
            card(9, type = ItemType.Kana, lapses = 9),
        )
        val weak = progress(cards).weak
        assertEquals(listOf(1, 2), weak.map { it.word.id })
        assertEquals(listOf(3, 1), weak.map { it.lapses })
        assertTrue(weak.all { (it.retrievability ?: 0.0) in 0.0..1.0 })
    }

    @Test
    fun weakTiesGoToTheFadingWord() {
        val older = card(1, lapses = 1, stability = 3.0).copy(lastReview = now.minus(Duration.ofDays(20)).toEpochMilli())
        val fresh = card(2, lapses = 1, stability = 3.0)
        val weak = progress(listOf(fresh, older)).weak
        assertEquals(listOf(1, 2), weak.map { it.word.id }) // word 1 hasn't been seen for 20 days
    }

    @Test
    fun forecastBucketsByStudyDayWithOverdueInToday() {
        // 12:00 in Tokyo on March 10: today runs from 04:00 on the 10th to 04:00 on the 11th.
        val cards = listOf(
            card(1, due = now.minus(Duration.ofDays(3))), // overdue → today
            card(2, due = now.plus(Duration.ofHours(2))), // later today
            card(3, due = Instant.parse("2026-03-10T20:00:00Z")), // 05:00 on the 11th → tomorrow
            card(4, due = Instant.parse("2026-03-16T10:00:00Z")), // seventh day
            card(5, due = Instant.parse("2026-03-20T00:00:00Z")), // beyond the week
            card(6, due = now, suspended = true),
            card(9, type = ItemType.Kana, due = now),
        )
        assertEquals(listOf(3, 1, 0, 0, 0, 0, 1), forecast(cards, now, tokyo))
    }
}
