package com.flurai.app.ui.tests

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.flurai.app.core.activity.ActivityTimer
import com.flurai.app.core.activity.StudyActivityRepository
import com.flurai.app.core.auth.AuthRepository
import com.flurai.app.core.grammar.GrammarRepository
import com.flurai.app.core.reading.ReadingRepository
import com.flurai.app.core.model.JlptLevel
import com.flurai.app.core.profile.StudyProfile
import com.flurai.app.core.profile.StudyProfileRepository
import com.flurai.app.core.quiz.CHECKPOINT_PASS_FRACTION
import com.flurai.app.core.quiz.PlacementTest
import com.flurai.app.core.quiz.Question
import com.flurai.app.core.quiz.QuizRepository
import com.flurai.app.core.quiz.TestResultEntity
import com.flurai.app.core.quiz.TestType
import com.flurai.app.core.study.ItemRef
import com.flurai.app.core.study.ItemType
import com.flurai.app.core.study.StudyRepository
import com.flurai.app.ui.QuizRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

/** Which test the quiz screen is running; round-trips through the navigation argument. */
sealed interface QuizKind {
    data object Placement : QuizKind
    data class Checkpoint(val jlpt: Int) : QuizKind
    data object Quick : QuizKind

    /** Fill-in-the-blank grammar practice for one level, or one point when [pointId] is not 0. */
    data class Grammar(val jlpt: Int, val pointId: Int = 0) : QuizKind

    /** "What does this sentence mean?" over Tatoeba sentences of one level. */
    data class Reading(val jlpt: Int) : QuizKind

    fun encode(): String = when (this) {
        Placement -> "placement"
        is Checkpoint -> "checkpoint-$jlpt"
        Quick -> "quick"
        is Grammar -> "grammar-$jlpt-$pointId"
        is Reading -> "reading-$jlpt"
    }

    companion object {
        fun decode(value: String): QuizKind = when {
            value == "placement" -> Placement
            value.startsWith("checkpoint-") -> Checkpoint(value.removePrefix("checkpoint-").toInt())
            value.startsWith("grammar-") -> value.removePrefix("grammar-").split("-").let { Grammar(it[0].toInt(), it[1].toInt()) }
            value.startsWith("reading-") -> Reading(value.removePrefix("reading-").toInt())
            else -> Quick
        }
    }
}

data class QuizResult(
    val title: String,
    val correct: Int,
    val total: Int,
    /** Checkpoint only: reached the pass mark. */
    val passed: Boolean?,
    /** Placement only: the hardest level passed. */
    val placementLevel: JlptLevel?,
    val missedWordIds: List<Int>,
    val currentLevel: JlptLevel?,
    val missedAdded: Boolean = false,
    val levelApplied: Boolean = false,
)

data class QuizUiState(
    val title: String = "",
    val loading: Boolean = true,
    val question: Question? = null,
    /** 1-based number of the current question. */
    val number: Int = 0,
    /** Known total, or null for the adaptive placement test. */
    val total: Int? = null,
    val selected: Int? = null,
    val result: QuizResult? = null,
    val error: String? = null,
)

@HiltViewModel
class QuizViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val auth: AuthRepository,
    private val profiles: StudyProfileRepository,
    private val quizzes: QuizRepository,
    private val grammar: GrammarRepository,
    private val reading: ReadingRepository,
    private val study: StudyRepository,
    private val activity: StudyActivityRepository,
) : ViewModel() {

    private val kind = QuizKind.decode(savedStateHandle.toRoute<QuizRoute>().kind)
    private val _state = MutableStateFlow(QuizUiState(title = titleOf(kind)))
    val state: StateFlow<QuizUiState> = _state.asStateFlow()

    private val queue = ArrayDeque<Question>()
    private val answered = mutableListOf<Pair<Question, Boolean>>()
    private val placement = PlacementTest()
    private val timer = ActivityTimer()
    private var profile: StudyProfile? = null
    private val uid: String? get() = auth.currentUser.value?.uid

    init {
        viewModelScope.launch {
            val uid = uid ?: return@launch
            profile = profiles.profile(uid).first()
            val questions = when (kind) {
                QuizKind.Placement -> quizzes.placementStage(placement.level, STAGE_SIZE)
                is QuizKind.Checkpoint -> quizzes.checkpoint(kind.jlpt)
                QuizKind.Quick -> quizzes.quick(uid, profile?.level ?: JlptLevel.N5)
                is QuizKind.Reading -> reading.questions(kind.jlpt)
                is QuizKind.Grammar -> grammar.practice(uid, kind.jlpt, kind.pointId.takeIf { it != 0 })
            }
            if (questions.isEmpty()) {
                _state.update { it.copy(loading = false, error = "Not enough material to build this test yet.") }
                return@launch
            }
            queue.addAll(questions)
            timer.start()
            showNext(total = if (kind == QuizKind.Placement) null else questions.size)
        }
    }

    fun choose(index: Int) {
        val current = _state.value.question ?: return
        if (_state.value.selected != null) return
        val correct = index == current.answer
        answered += current to correct
        if (kind == QuizKind.Placement) placement.answer(correct)
        credit(reviews = 1)
        val uid = uid
        val grammarId = current.grammarId
        if (uid != null && grammarId != null) {
            viewModelScope.launch { grammar.record(uid, grammarId, correct, Instant.now().toEpochMilli()) }
        }
        _state.update { it.copy(selected = index) }
    }

    fun next() {
        if (_state.value.selected == null) return
        viewModelScope.launch {
            credit(reviews = 0)
            if (queue.isNotEmpty()) return@launch showNext(_state.value.total)
            if (kind == QuizKind.Placement && !placement.finished) {
                _state.update { it.copy(loading = true) }
                queue.addAll(quizzes.placementStage(placement.level, STAGE_SIZE))
                if (queue.isNotEmpty()) return@launch showNext(null)
            }
            finish()
        }
    }

    private fun showNext(total: Int?) {
        val question = queue.removeFirst()
        _state.update {
            it.copy(loading = false, question = question, number = answered.size + 1, total = total, selected = null)
        }
    }

    private suspend fun finish() {
        val uid = uid ?: return
        val correct = answered.count { it.second }
        val total = answered.size
        val missed = answered.filter { !it.second }.mapNotNull { it.first.wordId }.distinct()
        val placementLevel = if (kind == QuizKind.Placement) placement.result else null
        val passed = (kind as? QuizKind.Checkpoint)?.let { total > 0 && correct.toDouble() / total >= CHECKPOINT_PASS_FRACTION }
        quizzes.save(
            TestResultEntity(
                uid = uid,
                type = when (kind) {
                    QuizKind.Placement -> TestType.Placement
                    is QuizKind.Checkpoint -> TestType.Checkpoint
                    QuizKind.Quick -> TestType.Quick
                    is QuizKind.Grammar -> TestType.Grammar
                    is QuizKind.Reading -> TestType.Reading
                },
                level = when (kind) {
                    QuizKind.Placement -> placementLevel?.jlpt ?: 0
                    is QuizKind.Checkpoint -> kind.jlpt
                    QuizKind.Quick -> null
                    is QuizKind.Grammar -> kind.jlpt
                    is QuizKind.Reading -> kind.jlpt
                },
                correct = correct,
                total = total,
                takenAt = Instant.now().toEpochMilli(),
            ),
        )
        activity.record(uid, Instant.now(), timer.stop(), 0)
        _state.update {
            it.copy(
                question = null,
                result = QuizResult(
                    title = titleOf(kind), correct = correct, total = total, passed = passed,
                    placementLevel = placementLevel, missedWordIds = missed, currentLevel = profile?.level,
                ),
            )
        }
    }

    /** Puts the words that were missed into the flashcards, as new cards for today. */
    fun addMissedToFlashcards() {
        val uid = uid ?: return
        val result = _state.value.result ?: return
        viewModelScope.launch {
            val now = Instant.now()
            result.missedWordIds.forEach { study.addToStudy(uid, ItemRef(ItemType.Word, it), now) }
            _state.update { it.copy(result = result.copy(missedAdded = true)) }
        }
    }

    /** Placement only: make the placement result the learner's study level. */
    fun applyPlacementLevel() {
        val uid = uid ?: return
        val current = profile ?: return
        val result = _state.value.result ?: return
        val level = result.placementLevel ?: return
        viewModelScope.launch {
            profiles.save(uid, current.copy(level = level))
            _state.update { it.copy(result = result.copy(levelApplied = true)) }
        }
    }

    private fun credit(reviews: Int) {
        val uid = uid ?: return
        activity.record(uid, Instant.now(), timer.tick(), reviews)
    }

    fun onPause() {
        val uid = uid ?: return
        activity.record(uid, Instant.now(), timer.stop(), 0)
    }

    fun onResume() {
        if (_state.value.result == null && !_state.value.loading) timer.start()
    }

    private companion object {
        const val STAGE_SIZE = 5

        fun titleOf(kind: QuizKind) = when (kind) {
            QuizKind.Placement -> "Placement test"
            is QuizKind.Checkpoint -> "N${kind.jlpt} checkpoint"
            QuizKind.Quick -> "Quick quiz"
            is QuizKind.Grammar -> "N${kind.jlpt} grammar"
            is QuizKind.Reading -> "N${kind.jlpt} reading"
        }
    }
}
