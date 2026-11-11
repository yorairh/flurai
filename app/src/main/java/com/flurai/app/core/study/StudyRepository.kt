package com.flurai.app.core.study

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.flurai.app.core.activity.SettingsRepository
import com.flurai.app.core.content.ContentDao
import com.flurai.app.core.model.JlptLevel
import com.flurai.app.core.profile.StudyProfile
import com.flurai.app.core.srs.FsrsScheduler
import com.flurai.app.core.srs.Rating
import com.flurai.app.core.srs.SrsCard
import com.flurai.app.core.srs.SrsState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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

    /** One of the learner's own decks. */
    data class Deck(val id: Long) : StudySource

    /** Round-trips through navigation arguments. */
    fun encode(): String = when (this) {
        Auto -> "auto"
        is Level -> "n$jlpt"
        is KanaDeck -> script.name
        is Deck -> "deck-$id"
    }

    companion object {
        fun decode(value: String): StudySource = when {
            value == "auto" -> Auto
            value.startsWith("deck-") -> Deck(value.removePrefix("deck-").toLong())
            value.startsWith("n") -> Level(value.drop(1).toInt())
            else -> KanaDeck(KanaScript.valueOf(value))
        }
    }
}

/** First JLPT list to take new words from for a starting level (Beginner starts at N5). */
fun JlptLevel.firstWordList(): Int = jlpt ?: 5

data class SessionData(val cards: List<CardEntity>, val newItems: List<ItemRef>, val endOfDay: Instant)

@Singleton
class StudyRepository @Inject constructor(
    private val study: StudyDao,
    private val content: ContentDao,
    private val settings: SettingsRepository,
    private val deckWords: DeckWords,
) {
    private val scheduler = FsrsScheduler()

    private suspend fun listeningOn(uid: String) = settings.settings(uid).first().listeningCards

    /** Cards due today; moves on by itself when the study day rolls over while a screen is open. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun dueCount(uid: String): Flow<Int> =
        combine(settings.settings(uid).map { it.listeningCards }.distinctUntilChanged(), StudyDay.ticks()) { l, now -> l to now }
            .flatMapLatest { (listening, now) ->
                study.dueCount(uid, StudyDay.end(now).toEpochMilli(), now.toEpochMilli(), listening)
            }

    /** New items started today (counts against the daily allowance); follows the rollover too. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun introducedToday(uid: String): Flow<Int> =
        StudyDay.ticks().flatMapLatest { now -> study.introducedSince(uid, StudyDay.start(now).toEpochMilli()) }

    fun introducedItems(uid: String): Flow<List<ItemRef>> = study.introducedItemsFlow(uid)

    fun cardsForItem(uid: String, item: ItemRef): Flow<List<CardEntity>> =
        study.cardsForItem(uid, item.itemType, item.itemId)

    @Volatile
    private var levels: Map<Int, Int>? = null

    /** Word id → JLPT number, read once (the content never changes while the app runs). */
    suspend fun wordLevels(): Map<Int, Int> =
        levels ?: content.wordLevels().associate { it.id to it.jlpt }.also { levels = it }

    /** Due cards plus today's remaining allowance of new items from [source]. */
    suspend fun loadSession(uid: String, profile: StudyProfile, source: StudySource, now: Instant): SessionData {
        val endOfDay = StudyDay.end(now)
        val due = study.dueCards(uid, endOfDay.toEpochMilli(), now.toEpochMilli(), listeningOn(uid))
        // Studying one chosen deck: only that deck's cards come up, due or new.
        val cards = when (source) {
            StudySource.Auto -> due
            is StudySource.Level -> {
                val levels = wordLevels()
                due.filter { it.itemType == ItemType.Word && levels[it.itemId] == source.jlpt }
            }
            is StudySource.KanaDeck -> due.filter { it.itemType == ItemType.Kana && KanaData.byId(it.itemId)?.script == source.script }
            is StudySource.Deck -> {
                val inDeck = deckWords.wordIds(source.id).toSet()
                due.filter { it.itemType == ItemType.Word && it.itemId in inDeck }
            }
        }
        // The daily allowance paces the automatic session. A deck you chose yourself always gets a batch.
        val allowance = if (source == StudySource.Auto) {
            (profile.newCardsPerDay - study.introducedSince(uid, StudyDay.start(now).toEpochMilli()).first()).coerceAtLeast(0)
        } else {
            profile.newCardsPerDay
        }
        val newItems = if (allowance == 0) emptyList() else newCandidates(uid, profile, source).take(allowance)
        return SessionData(cards, newItems, endOfDay)
    }

    /** Extra new items past the daily allowance: the learner asked to keep going. */
    suspend fun loadMore(uid: String, profile: StudyProfile, source: StudySource, count: Int): List<ItemRef> =
        newCandidates(uid, profile, source).take(count)

    /** Whether [source] still has items that were never studied. */
    suspend fun hasMoreNew(uid: String, profile: StudyProfile, source: StudySource): Boolean =
        newCandidates(uid, profile, source).isNotEmpty()

    /** JLPT lists that still have words not yet studied, for the "learn more" picker. */
    suspend fun levelsWithNewWords(uid: String): Set<Int> {
        val introduced = study.introducedItems(uid).filter { it.itemType == ItemType.Word }.map { it.itemId }.toSet()
        return wordLevels().filterKeys { it !in introduced }.values.toSet()
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
            is StudySource.Deck -> fresh(deckWords.wordIds(source.id).map { ItemRef(ItemType.Word, it) })
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
     * unlocks its production card (meaning → Japanese) and listening card, due from tomorrow.
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
            val due = StudyDay.end(now)
            listOfNotNull(
                newCard(uid, item.item, CardKind.Production, due = due, now = now),
                // Listening cards are only added while the setting is on (turning it on later adds them as you learn more).
                if (listeningOn(uid)) newCard(uid, item.item, CardKind.Listening, due = due, now = now) else null,
            )
        } else {
            emptyList()
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
     * Starts studying an item now (from its word page, Reading, a quiz or Media). It does not use
     * up the daily allowance: only words first met in a session count (see StudyDao.introducedSince).
     */
    suspend fun addToStudy(uid: String, item: ItemRef, now: Instant) =
        study.insertCardIfAbsent(newCard(uid, item, CardKind.Recognition, due = now, now = now))

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
