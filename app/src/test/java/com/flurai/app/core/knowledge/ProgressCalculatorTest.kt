package com.flurai.app.core.knowledge

import com.flurai.app.core.content.WordEntity
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
    )

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
    fun estimateStartsFromTheChosenLevelAndMovesUpOneLevelAtATime() {
        // Chose Beginner: nothing is assumed, so N5 must be learned first.
        assertEquals(LevelEstimate(JlptLevel.Beginner, JlptLevel.N5, 0.0), estimateLevel(emptyMap(), JlptLevel.Beginner))
        val passedN5 = estimateLevel(mapOf(5 to 0.85), JlptLevel.Beginner)
        assertEquals(JlptLevel.N5, passedN5.current)
        assertEquals(JlptLevel.N4, passedN5.goal)

        // Chose N5: it counts as reached, the goal is N4.
        val n5 = estimateLevel(emptyMap(), JlptLevel.N5)
        assertEquals(JlptLevel.N5, n5.current)
        assertEquals(JlptLevel.N4, n5.goal)

        // Chose N4: N3 at 90% moves to N3; N2 at 10% is the goal, 10% of the way to the 80% mark.
        val n3 = estimateLevel(mapOf(5 to 0.0, 3 to 0.9, 2 to 0.1), JlptLevel.N4)
        assertEquals(JlptLevel.N3, n3.current)
        assertEquals(JlptLevel.N2, n3.goal)
        assertEquals(0.125f, n3.goalProgress, 1e-6f)
    }

    @Test
    fun aGapStopsTheClimb() {
        // N4 is known but N5 is not: levels are passed in order, so the estimate stays at Beginner.
        val estimate = estimateLevel(mapOf(5 to 0.5, 4 to 0.95), JlptLevel.Beginner)
        assertEquals(JlptLevel.Beginner, estimate.current)
        assertEquals(0.5, estimate.goalFraction, 1e-9)
    }

    @Test
    fun topLevelHasNoGoal() {
        val estimate = estimateLevel(mapOf(5 to 1.0, 4 to 1.0, 3 to 1.0, 2 to 1.0, 1 to 0.9), JlptLevel.Beginner)
        assertEquals(JlptLevel.N1, estimate.current)
        assertNull(estimate.goal)
        assertEquals(1f, estimate.goalProgress, 0f)
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
        val snapshot = computeProgress(cards, catalog, JlptLevel.Beginner, now, tokyo)

        val (n5, n4) = snapshot.vocab.let { it[0] to it[1] }
        assertEquals(listOf(5, 4, 3, 2, 1), snapshot.vocab.map { it.jlpt })
        assertEquals(listOf(2, 1, 1, 0), listOf(n5.total, n5.mature, n5.learning, n5.known))
        assertEquals(listOf(1, 1, 0), listOf(n4.total, n4.known, n4.mature))
        assertEquals(3, snapshot.studiedWords)
        assertEquals(2, snapshot.knownWords)

        // 1 of 2 N5 words known: below 80%, so still Beginner with N5 as the goal at 50%.
        assertEquals(JlptLevel.Beginner, snapshot.estimate.current)
        assertEquals(JlptLevel.N5, snapshot.estimate.goal)
        assertEquals(0.5, snapshot.estimate.goalFraction, 1e-9)
    }

    @Test
    fun levelsAtOrBelowTheStartAreMarkedAssumed() {
        val snapshot = computeProgress(emptyList(), catalog, JlptLevel.N4, now, tokyo)
        assertEquals(listOf(true, true, false, false, false), snapshot.vocab.map { it.assumed })
    }

    @Test
    fun kanjiCountWhenTheyAppearInAKnownWord() {
        val cards = listOf(
            card(1, stability = 30.0), // 食べる: known, so 食 counts
            card(2, state = SrsState.Learning, stability = 2.0), // 水: still learning, doesn't count
            card(3, stability = 10.0), // 学校: known
        )
        val kanji = computeProgress(cards, catalog, JlptLevel.Beginner, now, tokyo).kanji
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
        val weak = computeProgress(cards, catalog, JlptLevel.Beginner, now, tokyo).weak
        assertEquals(listOf(1, 2), weak.map { it.word.id })
        assertEquals(listOf(3, 1), weak.map { it.lapses })
        assertTrue(weak.all { (it.retrievability ?: 0.0) in 0.0..1.0 })
    }

    @Test
    fun weakTiesGoToTheFadingWord() {
        val older = card(1, lapses = 1, stability = 3.0).copy(lastReview = now.minus(Duration.ofDays(20)).toEpochMilli())
        val fresh = card(2, lapses = 1, stability = 3.0)
        val weak = computeProgress(listOf(fresh, older), catalog, JlptLevel.Beginner, now, tokyo).weak
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
