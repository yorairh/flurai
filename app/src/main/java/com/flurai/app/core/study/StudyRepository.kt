package com.flurai.app.core.study

import com.flurai.app.core.content.ContentDao
import com.flurai.app.core.model.JlptLevel
import com.flurai.app.core.profile.StudyProfile
import com.flurai.app.core.srs.FsrsScheduler
import com.flurai.app.core.srs.Rating
import com.flurai.app.core.srs.SrsCard
import com.flurai.app.core.srs.SrsState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Where new cards come from. [Auto] follows the learner's level; the others are a chosen deck. */
sealed interface StudySource {
    data object Auto : StudySource
    data class Level(val jlpt: Int) : StudySource
    data class KanaDeck(val script: KanaScript) : StudySource

    /** Round-trips through navigation arguments. */
    fun encode(): String = when (this) {
        Auto -> "auto"
        is Level -> "n$jlpt"
        is KanaDeck -> script.name
    }

    companion object {
        fun decode(value: String): StudySource = when {
            value == "auto" -> Auto
            value.startsWith("n") -> Level(value.drop(1).toInt())
            else -> KanaDeck(KanaScript.valueOf(value))
        }
    }
}

/** First JLPT list to take new words from for a starting level (Beginner starts at N5). */
fun JlptLevel.firstWordList(): Int = when (this) {
    JlptLevel.Beginner, JlptLevel.N5 -> 5
    JlptLevel.N4 -> 4
    JlptLevel.N3 -> 3
    JlptLevel.N2 -> 2
    JlptLevel.N1 -> 1
}

data class SessionData(val cards: List<CardEntity>, val newItems: List<ItemRef>, val endOfDay: Instant)

@Singleton
class StudyRepository @Inject constructor(
    private val study: StudyDao,
    private val content: ContentDao,
) {
    private val scheduler = FsrsScheduler()

    fun dueCount(uid: String, now: Instant): Flow<Int> =
        study.dueCount(uid, StudyDay.end(now).toEpochMilli(), now.toEpochMilli())

    fun introducedToday(uid: String, now: Instant): Flow<Int> =
        study.introducedSince(uid, StudyDay.start(now).toEpochMilli())

    fun introducedItems(uid: String): Flow<List<ItemRef>> = study.introducedItemsFlow(uid)

    fun cardsForItem(uid: String, item: ItemRef): Flow<List<CardEntity>> =
        study.cardsForItem(uid, item.itemType, item.itemId)

    suspend fun wordLevels(): Map<Int, Int> = content.wordLevels().associate { it.id to it.jlpt }

    /** Due cards plus today's remaining allowance of new items from [source]. */
    suspend fun loadSession(uid: String, profile: StudyProfile, source: StudySource, now: Instant): SessionData {
        val endOfDay = StudyDay.end(now)
        val cards = study.dueCards(uid, endOfDay.toEpochMilli(), now.toEpochMilli())
        val introducedToday = study.introducedSince(uid, StudyDay.start(now).toEpochMilli())
        val allowance = (profile.newCardsPerDay - introducedToday.first()).coerceAtLeast(0)
        val newItems = if (allowance == 0) emptyList() else newCandidates(uid, profile, source).take(allowance)
        return SessionData(cards, newItems, endOfDay)
    }

    /** Items not yet studied, in teaching order. */
    private suspend fun newCandidates(uid: String, profile: StudyProfile, source: StudySource): List<ItemRef> {
        val introduced = study.introducedItems(uid).toSet()
        fun fresh(items: List<ItemRef>) = items.filter { it !in introduced }
        fun kana(script: KanaScript) = fresh(KanaData.of(script).map { ItemRef(ItemType.Kana, it.id) })
        suspend fun words(jlpt: Int) = fresh(content.wordIdsInStudyOrder(jlpt).map { ItemRef(ItemType.Word, it) })

        return when (source) {
            is StudySource.KanaDeck -> kana(source.script)
            is StudySource.Level -> words(source.jlpt)
            StudySource.Auto -> {
                if (profile.level == JlptLevel.Beginner) {
                    if (!profile.knowsHiragana) kana(KanaScript.Hiragana).ifEmpty { null }?.let { return it }
                    if (!profile.knowsKatakana) kana(KanaScript.Katakana).ifEmpty { null }?.let { return it }
                }
                for (jlpt in profile.level.firstWordList() downTo 1) {
                    val remaining = words(jlpt)
                    if (remaining.isNotEmpty()) return remaining
                }
                emptyList()
            }
        }
    }

    fun preview(card: CardEntity?, now: Instant): Map<Rating, Duration> =
        scheduler.preview(card?.toSrs() ?: SrsCard(due = now), now)

    /**
     * Schedules an answer and saves it. Learning a word's recognition card for the first time
     * unlocks its production card (meaning → Japanese), due from tomorrow.
     */
    suspend fun answer(
        uid: String,
        item: QueueItem,
        rating: Rating,
        now: Instant,
        duration: Duration,
    ): CardEntity {
        val previous = (item as? QueueItem.Existing)?.card
        val before = previous?.toSrs() ?: SrsCard(due = now)
        val after = scheduler.review(before, rating, now)
        val nowMs = now.toEpochMilli()
        val updated = CardEntity(
            uid = uid,
            itemType = item.item.itemType,
            itemId = item.item.itemId,
            kind = item.kind,
            state = after.state.value,
            step = after.step,
            stability = after.stability,
            difficulty = after.difficulty,
            due = after.due.toEpochMilli(),
            lastReview = nowMs,
            reps = (previous?.reps ?: 0) + 1,
            lapses = (previous?.lapses ?: 0) + if (before.state == SrsState.Review && rating == Rating.Again) 1 else 0,
            suspended = false,
            buriedUntil = null,
            createdAt = previous?.createdAt ?: nowMs,
        )
        val unlocked = if (
            item.item.itemType == ItemType.Word && item.kind == CardKind.Recognition &&
            before.state != SrsState.Review && after.state == SrsState.Review
        ) {
            newCard(uid, item.item, CardKind.Production, due = StudyDay.end(now), now = now)
        } else {
            null
        }
        val log = ReviewLogEntity(
            uid = uid,
            itemType = item.item.itemType,
            itemId = item.item.itemId,
            kind = item.kind,
            rating = rating.value,
            reviewedAt = nowMs,
            stateBefore = if (previous == null) 0 else before.state.value,
            intervalMs = Duration.between(now, after.due).toMillis(),
            durationMs = duration.toMillis(),
        )
        study.recordReview(updated, log, unlocked)
        return updated
    }

    suspend fun setSuspended(uid: String, item: ItemRef, suspended: Boolean) =
        study.setSuspended(uid, item.itemType, item.itemId, suspended)

    suspend fun buryUntilTomorrow(uid: String, item: ItemRef, now: Instant) =
        study.bury(uid, item.itemType, item.itemId, StudyDay.end(now).toEpochMilli())

    suspend fun reset(uid: String, item: ItemRef) = study.resetItem(uid, item.itemType, item.itemId)

    /**
     * Starts studying an item now (from its word page). createdAt 0 keeps it out of today's
     * new-card count: picking a word yourself shouldn't use up the daily allowance.
     */
    suspend fun addToStudy(uid: String, item: ItemRef, now: Instant) =
        study.insertCardIfAbsent(newCard(uid, item, CardKind.Recognition, due = now, now = now).copy(createdAt = 0))

    private fun newCard(uid: String, item: ItemRef, kind: CardKind, due: Instant, now: Instant) = CardEntity(
        uid = uid, itemType = item.itemType, itemId = item.itemId, kind = kind,
        state = SrsState.Learning.value, step = 0, stability = null, difficulty = null,
        due = due.toEpochMilli(), lastReview = null, reps = 0, lapses = 0,
        suspended = false, buriedUntil = null, createdAt = now.toEpochMilli(),
    )
}

fun CardEntity.toSrs() = SrsCard(
    state = SrsState.of(state),
    step = step,
    stability = stability,
    difficulty = difficulty,
    due = Instant.ofEpochMilli(due),
    lastReview = lastReview?.let(Instant::ofEpochMilli),
)
