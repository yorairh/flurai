package com.flurai.app.core.study

import com.flurai.app.core.srs.SrsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class StudyQueueTest {
    private val now = Instant.parse("2026-03-10T12:00:00Z")
    private val endOfDay = now.plus(Duration.ofHours(10))

    private fun card(id: Int, state: SrsState, dueIn: Duration) = CardEntity(
        uid = "u", itemType = ItemType.Word, itemId = id, kind = CardKind.Recognition,
        state = state.value, step = if (state == SrsState.Review) null else 0, stability = 1.0, difficulty = 5.0,
        due = now.plus(dueIn).toEpochMilli(), lastReview = now.minus(Duration.ofDays(1)).toEpochMilli(),
        reps = 1, lapses = 0, suspended = false, buriedUntil = null, createdAt = 0,
    )

    @Test
    fun orderIsDueLearningThenReviewsThenNewThenLearnAhead() {
        val queue = StudyQueue(
            cards = listOf(
                card(1, SrsState.Review, Duration.ofHours(-2)),
                card(2, SrsState.Learning, Duration.ofMinutes(-1)),
                card(3, SrsState.Learning, Duration.ofMinutes(15)),
            ),
            newItems = listOf(ItemRef(ItemType.Word, 9)),
            endOfDay = endOfDay,
        )
        assertEquals(QueueCounts(new = 1, learning = 2, review = 1), queue.counts())
        val order = generateSequence { queue.next(now)?.also { answer(queue, it) } }.map { it.item.itemId }.toList()
        assertEquals(listOf(2, 1, 9, 3), order)
    }

    /** Pretend every answer pushes the card to tomorrow. */
    private fun answer(queue: StudyQueue, item: QueueItem) {
        val base = (item as? QueueItem.Existing)?.card ?: card(item.item.itemId, SrsState.Learning, Duration.ZERO)
        queue.update(item, base.copy(state = SrsState.Review.value, due = endOfDay.plusSeconds(1).toEpochMilli()))
    }

    @Test
    fun learningCardDueLaterStaysButIsNotShownEarly() {
        val queue = StudyQueue(listOf(card(1, SrsState.Learning, Duration.ofMinutes(45))), emptyList(), endOfDay)
        assertNull(queue.next(now))
        assertEquals(now.plus(Duration.ofMinutes(45)), queue.nextLearningDue())
    }

    @Test
    fun failedCardComesBackInTheSameSession() {
        val queue = StudyQueue(emptyList(), listOf(ItemRef(ItemType.Word, 5)), endOfDay)
        val first = queue.next(now)!!
        queue.update(first, card(5, SrsState.Learning, Duration.ofMinutes(1)))
        assertEquals(QueueCounts(new = 0, learning = 1, review = 0), queue.counts())
        assertEquals(5, queue.next(now.plus(Duration.ofMinutes(1)))!!.item.itemId)
    }

    @Test
    fun removeDropsAllKindsOfAnItem() {
        val production = card(1, SrsState.Review, Duration.ofHours(-1)).copy(kind = CardKind.Production)
        val queue = StudyQueue(listOf(card(1, SrsState.Review, Duration.ofHours(-1)), production), emptyList(), endOfDay)
        queue.remove(ItemRef(ItemType.Word, 1))
        assertNull(queue.next(now))
    }

    @Test
    fun studyDayRollsOverAtFourInTheMorning() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        val lateNight = Instant.parse("2026-03-10T17:30:00Z") // 02:30 on the 11th in Tokyo
        assertEquals(Instant.parse("2026-03-09T19:00:00Z"), StudyDay.start(lateNight, tokyo)) // 04:00 on the 10th
        val morning = Instant.parse("2026-03-10T19:30:00Z") // 04:30 on the 11th
        assertEquals(Instant.parse("2026-03-10T19:00:00Z"), StudyDay.start(morning, tokyo))
        assertTrue(StudyDay.end(morning, tokyo).isAfter(morning))
    }

    @Test
    fun kanaIdsAreStableAndScriptsMatch() {
        assertEquals("あ", KanaData.byId(1)!!.kana)
        assertEquals("ア", KanaData.byId(101)!!.kana)
        assertEquals("ン", KanaData.katakana.first { it.romaji == "n" }.kana)
        assertEquals(71, KanaData.hiragana.size)
        assertEquals(KanaData.hiragana.map { it.romaji }, KanaData.katakana.map { it.romaji })
    }
}
