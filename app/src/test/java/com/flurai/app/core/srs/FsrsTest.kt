package com.flurai.app.core.srs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

/** Expected values were produced by py-fsrs 6.3.2 (Scheduler(enable_fuzzing=False)). */
class FsrsTest {
    private val t0 = Instant.parse("2026-01-01T09:00:00Z")
    private val scheduler = FsrsScheduler(fuzz = null)

    private data class Step(
        val minutes: Long, val rating: Int, val state: Int, val step: Int?,
        val stability: Double, val difficulty: Double, val dueSeconds: Long,
    )

    @Test
    fun reviewSequenceMatchesPyFsrs() {
        val expected = listOf(
            Step(0, 3, 1, 1, 2.3065, 2.118103970459016, 600),
            Step(10, 3, 2, null, 2.3065, 2.111214235785395, 173400),
            Step(60 * 24 * 3, 3, 2, null, 10.971048263078135, 2.1043313908464483, 1209600),
            Step(60 * 24 * 20, 1, 3, 0, 1.6239739553606465, 7.389975788014609, 1728600),
            Step(60 * 24 * 20 + 10, 3, 2, null, 1.6527612103086953, 7.377814181523433, 1901400),
            Step(60 * 24 * 40, 4, 2, null, 21.149334301677914, 6.486830244144543, 5270400),
            Step(60 * 24 * 41, 2, 2, null, 22.397077401908856, 7.653022571649363, 5443200),
        )
        var card = SrsCard(due = t0)
        for (e in expected) {
            card = scheduler.review(card, Rating.entries.first { it.value == e.rating }, t0.plusSeconds(e.minutes * 60))
            val label = "after ${e.minutes} min"
            assertEquals(label, e.state, card.state.value)
            assertEquals(label, e.step, card.step)
            assertEquals(label, e.stability, card.stability!!, 1e-9)
            assertEquals(label, e.difficulty, card.difficulty!!, 1e-9)
            assertEquals(label, e.dueSeconds, Duration.between(t0, card.due).seconds)
        }
        assertEquals(0.9108976882518997, scheduler.retrievability(card, t0.plus(Duration.ofDays(60))), 1e-12)
    }

    @Test
    fun firstReviewOfANewCard() {
        val preview = scheduler.preview(SrsCard(due = t0), t0)
        assertEquals(Duration.ofMinutes(1), preview[Rating.Again])
        assertEquals(Duration.ofSeconds(330), preview[Rating.Hard])
        assertEquals(Duration.ofMinutes(10), preview[Rating.Good])
        assertEquals(Duration.ofDays(8), preview[Rating.Easy])
    }

    @Test
    fun fuzzStaysWithinRange() {
        val fuzzy = FsrsScheduler(fuzz = Random(42))
        val review = SrsCard(SrsState.Review, null, stability = 30.0, difficulty = 5.0, due = t0, lastReview = t0.minus(Duration.ofDays(30)))
        val exact = Duration.between(t0, scheduler.review(review, Rating.Good, t0).due).toDays()
        repeat(50) {
            val days = Duration.between(t0, fuzzy.review(review, Rating.Good, t0).due).toDays()
            assertTrue("$days vs $exact", kotlin.math.abs(days - exact) <= exact * 0.1 + 2)
        }
    }
}
