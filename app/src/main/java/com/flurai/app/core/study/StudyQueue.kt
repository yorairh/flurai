package com.flurai.app.core.study

import com.flurai.app.core.srs.SrsState
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Study days start at 4 a.m. (like Anki), so a late-night session counts toward the evening's day. */
object StudyDay {
    private val ROLLOVER: LocalTime = LocalTime.of(4, 0)

    fun start(now: Instant, zone: ZoneId = ZoneId.systemDefault()): Instant {
        val local = ZonedDateTime.ofInstant(now, zone)
        var start = local.toLocalDate().atTime(ROLLOVER).atZone(zone)
        if (local.isBefore(start)) start = start.minusDays(1)
        return start.toInstant()
    }

    fun end(now: Instant, zone: ZoneId = ZoneId.systemDefault()): Instant =
        ZonedDateTime.ofInstant(start(now, zone), zone).plusDays(1).toInstant()
}

/** Something to study next: an existing card, or a new item shown for the first time. */
sealed interface QueueItem {
    val item: ItemRef
    val kind: CardKind

    data class Existing(val card: CardEntity) : QueueItem {
        override val item get() = ItemRef(card.itemType, card.itemId)
        override val kind get() = card.kind
    }

    data class New(override val item: ItemRef) : QueueItem {
        override val kind get() = CardKind.Recognition
    }
}

data class QueueCounts(val new: Int, val learning: Int, val review: Int)

/**
 * The order cards come up in a session, Anki-style: learning cards that are due, then reviews due
 * today, then new cards; when only learning cards remain, ones due within [learnAhead] are shown early.
 */
class StudyQueue(
    cards: List<CardEntity>,
    newItems: List<ItemRef>,
    private val endOfDay: Instant,
    private val learnAhead: Duration = Duration.ofMinutes(20),
) {
    private val cards = cards.associateBy { key(it) }.toMutableMap()
    private val newItems = ArrayDeque(newItems)

    private fun key(c: CardEntity) = Triple(c.itemType, c.itemId, c.kind)
    private fun CardEntity.inLearning() = state != SrsState.Review.value

    fun counts(): QueueCounts {
        val learning = cards.values.count { it.inLearning() }
        return QueueCounts(new = newItems.size, learning = learning, review = cards.size - learning)
    }

    fun next(now: Instant): QueueItem? {
        val nowMs = now.toEpochMilli()
        val learning = cards.values.filter { it.inLearning() }.sortedBy { it.due }
        learning.firstOrNull { it.due <= nowMs }?.let { return QueueItem.Existing(it) }
        cards.values.filter { !it.inLearning() && it.due < endOfDay.toEpochMilli() }
            .minByOrNull { it.due }?.let { return QueueItem.Existing(it) }
        newItems.firstOrNull()?.let { return QueueItem.New(it) }
        learning.firstOrNull { it.due <= nowMs + learnAhead.toMillis() }?.let { return QueueItem.Existing(it) }
        return null
    }

    /** Records the new state of a card after an answer; it stays in the session only if due again today. */
    fun update(answered: QueueItem, card: CardEntity) {
        if (answered is QueueItem.New) newItems.remove(answered.item)
        if (card.due < endOfDay.toEpochMilli() && card.inLearning()) cards[key(card)] = card else cards.remove(key(card))
    }

    /** Removes an item entirely (suspended or buried during the session). */
    fun remove(item: ItemRef) {
        newItems.remove(item)
        cards.keys.removeAll { it.first == item.itemType && it.second == item.itemId }
    }

    /** When the session has nothing to show now, the time the next learning card becomes due. */
    fun nextLearningDue(): Instant? = cards.values.filter { it.inLearning() }.minOfOrNull { it.due }?.let(Instant::ofEpochMilli)
}
