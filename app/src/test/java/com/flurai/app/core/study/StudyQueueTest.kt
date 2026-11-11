package com.flurai.app.core.study

import com.flurai.app.core.srs.SrsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

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
    fun extraNewItemsCanBeAddedAfterTheQueueRunsOut() {
        val now = Instant.parse("2025-01-01T10:00:00Z")
        val queue = StudyQueue(emptyList(), emptyList(), now.plus(Duration.ofHours(8)))
        assertNull(queue.next(now))
        queue.addNew(listOf(ItemRef(ItemType.Word, 1), ItemRef(ItemType.Word, 2)))
        queue.addNew(listOf(ItemRef(ItemType.Word, 2))) // no duplicates
        assertEquals(2, queue.counts().new)
        assertEquals(ItemRef(ItemType.Word, 1), queue.next(now)?.item)
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

    private fun reviews(count: Int) = (1..count).map { card(it, SrsState.Review, Duration.ofHours(-1)) }

    /** Answers every card as Good (back to tomorrow) and returns the order the item ids came in. */
    private fun order(queue: StudyQueue) = generateSequence { queue.next(now)?.also { answer(queue, it) } }.map { it.item.itemId }.toList()

    @Test
    fun reviewsAreNotAlwaysInTheSameOrder() {
        val orders = (1..6).map { seed -> order(StudyQueue(reviews(12), emptyList(), endOfDay, random = Random(seed))) }
        assertTrue("six sessions gave the same order", orders.distinct().size > 1)
        orders.forEach { assertEquals((1..12).toList(), it.sorted()) } // every card still comes up once
    }

    @Test
    fun aFailedCardDoesNotComeStraightBackWhileOthersAreWaiting() {
        repeat(20) { seed ->
            val queue = StudyQueue(reviews(8), emptyList(), endOfDay, random = Random(seed))
            val first = queue.next(now)!!
            queue.update(first, (first as QueueItem.Existing).card.copy(state = SrsState.Learning.value, step = 0, due = now.toEpochMilli()))
            assertTrue("seed $seed repeated the failed card", queue.next(now)!!.item != first.item)
        }
    }

    @Test
    fun aLoneFailedCardStillComesBack() {
        val queue = StudyQueue(reviews(1), emptyList(), endOfDay, random = Random(1))
        val first = queue.next(now)!! as QueueItem.Existing
        queue.update(first, first.card.copy(state = SrsState.Learning.value, step = 0, due = now.toEpochMilli()))
        assertEquals(first.item, queue.next(now)!!.item)
    }

    @Test
    fun cardsOfTheSameWordAreSpacedApart() {
        // Ten words, each with a production and a listening card, all due.
        val cards = (1..10).flatMap { id ->
            listOf(CardKind.Production, CardKind.Listening).map { kind -> card(id, SrsState.Review, Duration.ofHours(-1)).copy(kind = kind) }
        }
        repeat(20) { seed ->
            val order = order(StudyQueue(cards, emptyList(), endOfDay, random = Random(seed)))
            assertEquals(20, order.size)
            // The last few cards have nothing else to be shown between them, so only the rest must be spaced.
            val spacedPart = order.size - StudyQueue.SIBLING_SPACING
            order.forEachIndexed { index, id ->
                val window = order.drop(index + 1).take(StudyQueue.SIBLING_SPACING).withIndex().filter { (i, _) -> index + 1 + i < spacedPart }
                assertTrue("seed $seed: word $id came again too soon: $order", window.none { it.value == id })
            }
        }
    }

    @Test
    fun aCardAddedByHandCountsAsNewAndComesAfterReviews() {
        val handAdded = card(7, SrsState.Learning, Duration.ZERO).copy(reps = 0, lastReview = null)
        val queue = StudyQueue(listOf(handAdded, card(1, SrsState.Review, Duration.ofHours(-1))), emptyList(), endOfDay, random = Random(1))
        assertEquals(QueueCounts(new = 1, learning = 0, review = 1), queue.counts())
        assertEquals(listOf(1, 7), order(queue))
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
