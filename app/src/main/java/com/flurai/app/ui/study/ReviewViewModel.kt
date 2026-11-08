package com.flurai.app.ui.study

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.flurai.app.core.activity.ActivityTimer
import com.flurai.app.core.activity.StudyActivityRepository
import com.flurai.app.core.auth.AuthRepository
import com.flurai.app.core.content.ContentDao
import com.flurai.app.core.content.SentenceEntity
import com.flurai.app.core.content.WordEntity
import com.flurai.app.core.profile.StudyProfileRepository
import com.flurai.app.core.speech.JapaneseSpeaker
import com.flurai.app.core.srs.Rating
import com.flurai.app.core.study.ItemRef
import com.flurai.app.core.study.ItemType
import com.flurai.app.core.study.Kana
import com.flurai.app.core.study.KanaData
import com.flurai.app.core.study.QueueCounts
import com.flurai.app.core.study.QueueItem
import com.flurai.app.core.study.StudyQueue
import com.flurai.app.core.study.StudyRepository
import com.flurai.app.core.study.StudySource
import com.flurai.app.ui.StudyRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.util.Locale
import javax.inject.Inject

/** What a card shows. */
sealed interface CardContent {
    data class Word(val word: WordEntity, val example: SentenceEntity?) : CardContent
    data class KanaCard(val kana: Kana) : CardContent

    /** Text to read aloud: the kana reading, so the speech engine can't misread kanji. */
    val speech: String
        get() = when (this) {
            is Word -> word.reading
            is KanaCard -> kana.kana
        }
}

data class CurrentCard(val item: QueueItem, val content: CardContent, val isNew: Boolean)

data class ReviewUiState(
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
)

@HiltViewModel
class ReviewViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val auth: AuthRepository,
    private val profiles: StudyProfileRepository,
    private val repository: StudyRepository,
    private val activity: StudyActivityRepository,
    private val content: ContentDao,
    val speaker: JapaneseSpeaker,
) : ViewModel() {

    private val source = StudySource.decode(savedStateHandle.toRoute<StudyRoute>().source)
    private val _state = MutableStateFlow(ReviewUiState())
    val state: StateFlow<ReviewUiState> = _state.asStateFlow()

    private var queue: StudyQueue? = null
    private val timer = ActivityTimer()
    private var wasTiming = false
    private var shownAt: Instant = Instant.now()
    private val uid: String? get() = auth.currentUser.value?.uid

    init {
        speaker.start()
        viewModelScope.launch {
            val uid = uid ?: return@launch
            val profile = profiles.profile(uid).first() ?: return@launch
            val session = repository.loadSession(uid, profile, source, Instant.now())
            queue = StudyQueue(session.cards, session.newItems, session.endOfDay)
            timer.start()
            showNext()
        }
    }

    /** Audio plays with the answer, never before: hearing a word gives its reading away. */
    fun reveal() {
        credit(reviews = 0)
        _state.update { it.copy(revealed = true) }
        _state.value.current?.let { speaker.speak(it.content.speech) }
    }

    fun answer(rating: Rating) {
        val uid = uid ?: return
        val current = _state.value.current ?: return
        if (!_state.value.revealed) return
        _state.update { it.copy(current = null) } // ignore double taps while saving
        viewModelScope.launch {
            val now = Instant.now()
            credit(reviews = 1)
            val updated = repository.answer(uid, current.item, rating, now, Duration.between(shownAt, now))
            queue?.update(current.item, updated)
            _state.update { it.copy(answeredCount = it.answeredCount + 1) }
            showNext()
        }
    }

    /** Hide this word until tomorrow. */
    fun buryCurrent() = removeCurrent { uid, item -> repository.buryUntilTomorrow(uid, item, Instant.now()) }

    /** Stop showing this word until it's resumed from its word page. */
    fun suspendCurrent() = removeCurrent { uid, item -> repository.setSuspended(uid, item, true) }

    fun speakCurrent() {
        _state.value.current?.let { speaker.speak(it.content.speech) }
    }

    /** After finishing: keep going with [count] more new words from [from] (default: the same deck). */
    fun learnMore(from: StudySource = source, count: Int = MORE_WORDS) {
        val uid = uid ?: return
        val queue = queue ?: return
        _state.update { it.copy(loading = true) }
        timer.start()
        viewModelScope.launch {
            val profile = profiles.profile(uid).first()
            val items = if (profile == null) emptyList() else repository.loadMore(uid, profile, from, count)
            queue.addNew(items)
            if (items.isEmpty()) _state.update { it.copy(loading = false, finished = true, noMoreWords = true) } else showNext()
        }
    }

    /** Adds the time since the last tap (capped at two minutes) to today's study time. */
    private fun credit(reviews: Int) {
        val uid = uid ?: return
        activity.record(uid, Instant.now(), timer.tick(), reviews)
    }

    /** The app left the foreground or the screen closed: stop the clock. */
    fun onPause() {
        val uid = uid ?: return
        wasTiming = true
        activity.record(uid, Instant.now(), timer.stop(), 0)
    }

    /** Back in the foreground: the clock restarts (time away is not counted). */
    fun onResume() {
        if (wasTiming && !_state.value.finished) timer.start()
    }

    /** Called when the screen comes back into view, so learning cards that became due show up. */
    fun refresh() {
        if (_state.value.finished && _state.value.comeBackAt != null) viewModelScope.launch { showNext() }
    }

    private fun removeCurrent(action: suspend (String, ItemRef) -> Unit) {
        val uid = uid ?: return
        val current = _state.value.current ?: return
        viewModelScope.launch {
            // A brand-new item has no card yet; it's simply skipped for this session.
            if (current.item is QueueItem.Existing) action(uid, current.item.item)
            queue?.remove(current.item.item)
            showNext()
        }
    }

    private suspend fun showNext() {
        val queue = queue ?: return
        val now = Instant.now()
        val next = queue.next(now)
        if (next == null) {
            uid?.let { activity.record(it, now, timer.stop(), 0) }
            val levels = uid?.let { repository.levelsWithNewWords(it) }.orEmpty()
            _state.update {
                it.copy(
                    loading = false, current = null, finished = true, counts = queue.counts(),
                    comeBackAt = queue.nextLearningDue(), levelsWithWords = levels, noMoreWords = false,
                )
            }
            return
        }
        val cardContent = load(next) ?: run {
            queue.remove(next.item) // content missing (e.g. word removed from the lists): skip it
            return showNext()
        }
        shownAt = now
        val existing = (next as? QueueItem.Existing)?.card
        _state.update {
            it.copy(
                loading = false,
                current = CurrentCard(next, cardContent, isNew = existing == null),
                revealed = false,
                counts = queue.counts(),
                intervals = repository.preview(existing, now).mapValues { (_, d) -> formatInterval(d) },
                finished = false,
                comeBackAt = null,
            )
        }
    }

    private suspend fun load(item: QueueItem): CardContent? = when (item.item.itemType) {
        ItemType.Kana -> KanaData.byId(item.item.itemId)?.let { CardContent.KanaCard(it) }
        ItemType.Word -> content.word(item.item.itemId)?.let { word ->
            CardContent.Word(word, content.sentencesForWord(word.id).firstOrNull())
        }
    }
}

private const val MORE_WORDS = 10

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
