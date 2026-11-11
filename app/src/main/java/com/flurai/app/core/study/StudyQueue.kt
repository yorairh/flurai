package com.flurai.app.core.study

import com.flurai.app.core.srs.SrsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

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

    /** The current time now, and again each time a new study day begins (for counts shown on screen). */
    fun ticks(clock: () -> Instant = Instant::now): Flow<Instant> = flow {
        while (true) {
            val now = clock()
            emit(now)
            delay(Duration.between(now, end(now)).toMillis().coerceAtLeast(1_000))
        }
    }
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
 * today, then new cards (never answered: new words and ones added by hand); when only learning
 * cards remain, ones due within [learnAhead] are shown early.
 *
 * Within a step the card is picked at random, so the same words do not come in the same sequence
 * every session or every day. An item answered during the last [spacing] cards is held back while
 * anything else is available, so a failed card does not bounce straight back and a word's
 * Recognition, Production and Listening cards are never shown one after the other.
 */
class StudyQueue(
    cards: List<CardEntity>,
    newItems: List<ItemRef>,
    private val endOfDay: Instant,
    private val learnAhead: Duration = Duration.ofMinutes(20),
    private val random: Random = Random.Default,
    private val spacing: Int = SIBLING_SPACING,
) {
    private val cards = cards.associateBy { key(it) }.toMutableMap()
    private val newItems = ArrayDeque(newItems)
    private val recent = ArrayDeque<ItemRef>()

    private fun key(c: CardEntity) = Triple(c.itemType, c.itemId, c.kind)
    private fun CardEntity.inLearning() = state != SrsState.Review.value
    private fun CardEntity.unstarted() = reps == 0

    fun counts(): QueueCounts {
        val unstarted = cards.values.count { it.unstarted() }
        val learning = cards.values.count { it.inLearning() && !it.unstarted() }
        return QueueCounts(new = newItems.size + unstarted, learning = learning, review = cards.size - learning - unstarted)
    }

    private class Step(val items: List<QueueItem>, val inOrder: Boolean)

    private fun steps(now: Instant): List<Step> {
        val nowMs = now.toEpochMilli()
        val started = cards.values.filter { !it.unstarted() }
        val learningDue = started.filter { it.inLearning() && it.due <= nowMs }
        val reviews = started.filter { !it.inLearning() && it.due < endOfDay.toEpochMilli() }
        val fresh = cards.values.filter { it.unstarted() }.sortedBy { it.createdAt }.map { QueueItem.Existing(it) } + newItems.map { QueueItem.New(it) }
        val ahead = started.filter { it.inLearning() && it.due > nowMs && it.due <= nowMs + learnAhead.toMillis() }
        return listOf(
            Step(learningDue.map { QueueItem.Existing(it) }, inOrder = false),
            Step(reviews.map { QueueItem.Existing(it) }, inOrder = false),
            Step(fresh, inOrder = true),
            Step(ahead.sortedBy { it.due }.map { QueueItem.Existing(it) }, inOrder = true),
        )
    }

    fun next(now: Instant): QueueItem? {
        val steps = steps(now)
        // First choice: something that was not shown just now. Otherwise whatever there is.
        for (step in steps) {
            val fresh = step.items.filter { it.item !in recent }
            if (fresh.isNotEmpty()) return if (step.inOrder) fresh.first() else fresh.random(random)
        }
        for (step in steps) {
            if (step.items.isNotEmpty()) return if (step.inOrder) step.items.first() else step.items.random(random)
        }
        return null
    }

    /** Records the new state of a card after an answer; it stays in the session only if due again today. */
    fun update(answered: QueueItem, card: CardEntity) {
        if (answered is QueueItem.New) newItems.remove(answered.item)
        recent.addLast(answered.item)
        while (recent.size > spacing) recent.removeFirst()
        if (card.due < endOfDay.toEpochMilli() && card.inLearning()) cards[key(card)] = card else cards.remove(key(card))
    }

    /** Adds extra new items (the learner chose to keep going past the daily allowance). */
    fun addNew(items: List<ItemRef>) {
        items.filter { it !in newItems }.forEach(newItems::addLast)
    }

    /** Removes an item entirely (suspended or buried during the session). */
    fun remove(item: ItemRef) {
        newItems.remove(item)
        cards.keys.removeAll { it.first == item.itemType && it.second == item.itemId }
    }

    /** When the session has nothing to show now, the time the next learning card becomes due. */
    fun nextLearningDue(): Instant? =
        cards.values.filter { it.inLearning() && !it.unstarted() }.minOfOrNull { it.due }?.let(Instant::ofEpochMilli)

    companion object {
        /** How many cards must come up before a card of the same word (or the same card) may be shown again. */
        const val SIBLING_SPACING = 5
    }
}
