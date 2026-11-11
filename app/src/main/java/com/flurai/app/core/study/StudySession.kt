package com.flurai.app.core.study

import com.flurai.app.core.activity.StudyClock
import com.flurai.app.core.content.ContentDao
import com.flurai.app.core.content.SentenceEntity
import com.flurai.app.core.content.WordEntity
import com.flurai.app.core.profile.StudyProfile
import com.flurai.app.core.srs.Rating
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.random.Random
import javax.inject.Inject

/** What a card shows. */
sealed interface CardContent {
    data class Word(val word: WordEntity, val example: SentenceEntity?) : CardContent
    data class KanaCard(val kana: Kana, val example: WordEntity? = null) : CardContent

    /** Text to read aloud: the kana reading, so the speech engine can't misread kanji. */
    val speech: String
        get() = when (this) {
            is Word -> word.reading
            is KanaCard -> kana.kana
        }
}

/** Where a card's text comes from. The database in the app, a map in tests. */
interface CardContentSource {
    /** The content of [item], or null when it no longer exists (a word removed from the lists). */
    suspend fun contentFor(item: ItemRef): CardContent?
}

/** [CardContentSource] on the content database (words) and the built-in kana table. */
class DatabaseCardContent @Inject constructor(private val content: ContentDao) : CardContentSource {
    override suspend fun contentFor(item: ItemRef): CardContent? = when (item.itemType) {
        ItemType.Kana -> KanaData.byId(item.itemId)?.let { kana ->
            val example = if (kana.script == KanaScript.Hiragana) content.wordStartingWith(kana.kana) else content.loanwordStartingWith(kana.kana)
            CardContent.KanaCard(kana, example)
        }
        ItemType.Word -> content.word(item.itemId)?.let { word ->
            CardContent.Word(word, content.sentencesForWord(word.id).firstOrNull())
        }
    }
}

data class CurrentCard(val item: QueueItem, val content: CardContent, val isNew: Boolean)

data class SessionState(
    val loading: Boolean = true,
    val current: CurrentCard? = null,
    val revealed: Boolean = false,
    val counts: QueueCounts = QueueCounts(0, 0, 0),
    /** Interval labels for the four answer buttons, e.g. "10m", "3d". */
    val intervals: Map<Rating, String> = emptyMap(),
    val answeredCount: Int = 0,
    /** Set when nothing is left to show now. */
    val finished: Boolean = false,
    /** If cards are still in learning, when the next one is due. */
    val comeBackAt: Instant? = null,
    /** JLPT lists with words not studied yet, offered after finishing. */
    val levelsWithWords: Set<Int> = emptySet(),
    /** The learner asked for more words but that deck is used up. */
    val noMoreWords: Boolean = false,
    /** The session was for one chosen deck (not the automatic one). */
    val deckSession: Boolean = false,
    /** That chosen deck still has words never studied. */
    val deckHasMore: Boolean = false,
    /** Goes up by one each time a card is put on screen (so the screen can play a Listening card's audio once). */
    val shown: Int = 0,
)

/**
 * One flashcard session of one learner: which card comes next, revealing and answering it (saved
 * through [StudyRepository]), burying and suspending, asking for more words, and what the screen
 * should offer when nothing is left. Everything on screen is in [state]; the screen's own
 * concerns (speech, lifecycle, navigation) stay outside. [now] is passed in so a test can move time.
 *
 * Call [start] once. Methods that save are suspending; a second answer while one is being saved is ignored.
 */
class StudySession(
    private val uid: String,
    private val profile: StudyProfile,
    private val source: StudySource,
    private val repository: StudyRepository,
    private val content: CardContentSource,
    private val clock: StudyClock,
    private val now: () -> Instant = Instant::now,
    private val random: Random = Random.Default,
) {
    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private var queue: StudyQueue? = null
    private var shownAt: Instant = now()

    suspend fun start() {
        val data = repository.loadSession(uid, profile, source, now())
        queue = StudyQueue(data.cards, data.newItems, data.endOfDay, random = random)
        showNext()
    }

    fun reveal() {
        clock.tap()
        _state.update { it.copy(revealed = true) }
    }

    suspend fun answer(rating: Rating) {
        val current = _state.value.current ?: return
        if (!_state.value.revealed) return
        _state.update { it.copy(current = null) } // ignore double taps while saving
        val at = now()
        clock.tap(reviews = 1)
        val updated = repository.answer(uid, current.item, rating, at, Duration.between(shownAt, at))
        queue?.update(current.item, updated)
        _state.update { it.copy(answeredCount = it.answeredCount + 1) }
        showNext()
    }

    /** Hide this word until tomorrow. */
    suspend fun buryCurrent() = removeCurrent { repository.buryUntilTomorrow(uid, it, now()) }

    /** Stop showing this word until it's resumed from its word page. */
    suspend fun suspendCurrent() = removeCurrent { repository.setSuspended(uid, it, true) }

    /** After finishing: keep going with more new words from [from] (default: the same deck). */
    suspend fun learnMore(from: StudySource = source) {
        val queue = queue ?: return
        _state.update { it.copy(loading = true) }
        val items = repository.loadMore(uid, profile, from, MORE_WORDS)
        queue.addNew(items)
        if (items.isEmpty()) _state.update { it.copy(loading = false, finished = true, noMoreWords = true) } else showNext()
    }

    /** The screen came back into view: learning cards that became due while it was finished show up. */
    suspend fun refresh() {
        if (_state.value.finished && _state.value.comeBackAt != null) showNext()
    }

    /** The app left the foreground or the screen closed: stop the study clock. */
    fun pause() = clock.pause()

    /** Back in the foreground: the clock restarts (time away is not counted). */
    fun resume() = clock.resume()

    private suspend fun removeCurrent(action: suspend (ItemRef) -> Unit) {
        val current = _state.value.current ?: return
        // A brand-new item has no card yet; it's simply skipped for this session.
        if (current.item is QueueItem.Existing) action(current.item.item)
        queue?.remove(current.item.item)
        showNext()
    }

    private suspend fun showNext() {
        val queue = queue ?: return
        val at = now()
        val next = queue.next(at)
        if (next == null) {
            clock.finish()
            val chosen = source != StudySource.Auto
            val levels = if (chosen) emptySet() else repository.levelsWithNewWords(uid)
            val more = chosen && repository.hasMoreNew(uid, profile, source)
            _state.update {
                it.copy(
                    loading = false, current = null, finished = true, counts = queue.counts(),
                    comeBackAt = queue.nextLearningDue(), levelsWithWords = levels, noMoreWords = false,
                    deckSession = chosen, deckHasMore = more,
                )
            }
            return
        }
        val cardContent = content.contentFor(next.item) ?: run {
            queue.remove(next.item) // content missing (e.g. word removed from the lists): skip it
            return showNext()
        }
        shownAt = at
        clock.start() // also after "learn more" or a learning card coming back on the finished screen
        val existing = (next as? QueueItem.Existing)?.card
        _state.update {
            it.copy(
                loading = false,
                current = CurrentCard(next, cardContent, isNew = existing == null || existing.reps == 0),
                revealed = false,
                counts = queue.counts(),
                intervals = repository.preview(existing, at).mapValues { (_, d) -> formatInterval(d) },
                finished = false,
                comeBackAt = null,
                shown = it.shown + 1,
            )
        }
    }

    private companion object {
        const val MORE_WORDS = 10
    }
}

// Locale.US: a comma decimal separator would read oddly on the buttons.
private fun oneDecimal(x: Double) = String.format(Locale.US, "%.1f", x).removeSuffix(".0")

/** Short interval label for answer buttons: 45s → "<1m", 10 min → "10m", 3 days → "3d", 2 months → "2mo". */
fun formatInterval(d: Duration): String {
    val minutes = d.toMinutes()
    val days = d.toDays()
    return when {
        minutes < 1 -> "<1m"
        minutes < 60 -> "${minutes}m"
        d.toHours() < 24 -> "${d.toHours()}h"
        days < 30 -> "${days}d"
        days < 365 -> "${oneDecimal(days / 30.0)}mo"
        else -> "${oneDecimal(days / 365.0)}y"
    }
}
