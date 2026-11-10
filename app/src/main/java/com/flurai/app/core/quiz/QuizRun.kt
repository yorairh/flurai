package com.flurai.app.core.quiz

import com.flurai.app.core.activity.StudyClock
import com.flurai.app.core.grammar.GrammarRepository
import com.flurai.app.core.model.JlptLevel
import com.flurai.app.core.profile.StudyProfile
import com.flurai.app.core.profile.StudyProfileRepository
import com.flurai.app.core.reading.ReadingRepository
import com.flurai.app.core.study.ItemRef
import com.flurai.app.core.study.ItemType
import com.flurai.app.core.study.StudyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import javax.inject.Inject

/**
 * Which test a run is. Everything that differs between kinds is answered here, so adding a kind
 * means adding one case: how it is written in a navigation route, its title, the [TestType] it is
 * saved as, the level it is saved against, and whether it is passed.
 */
sealed interface QuizKind {
    data object Placement : QuizKind
    data class Checkpoint(val jlpt: Int) : QuizKind
    data object Quick : QuizKind

    /** Hear a word and pick its meaning. */
    data object Listening : QuizKind

    /** Fill-in-the-blank grammar practice for one level, or one point when [pointId] is not 0. */
    data class Grammar(val jlpt: Int, val pointId: Int = 0) : QuizKind

    /** "What does this sentence mean?" over Tatoeba sentences of one level. */
    data class Reading(val jlpt: Int) : QuizKind

    /** Round-trips through navigation arguments. */
    fun encode(): String = when (this) {
        Placement -> "placement"
        is Checkpoint -> "checkpoint-$jlpt"
        Quick -> "quick"
        Listening -> "listening"
        is Grammar -> "grammar-$jlpt-$pointId"
        is Reading -> "reading-$jlpt"
    }

    val title: String
        get() = when (this) {
            Placement -> "Placement test"
            is Checkpoint -> "N$jlpt checkpoint"
            Quick -> "Quick quiz"
            Listening -> "Listening quiz"
            is Grammar -> "N$jlpt grammar"
            is Reading -> "N$jlpt reading"
        }

    /** How the result is stored (and synced). */
    val testType: TestType
        get() = when (this) {
            Placement -> TestType.Placement
            is Checkpoint -> TestType.Checkpoint
            Quick -> TestType.Quick
            Listening -> TestType.Listening
            is Grammar -> TestType.Grammar
            is Reading -> TestType.Reading
        }

    /** The JLPT number the result is stored against; [placementLevel] is the level a placement test ended at. */
    fun savedLevel(placementLevel: JlptLevel?): Int? = when (this) {
        Placement -> placementLevel?.jlpt ?: 0
        is Checkpoint -> jlpt
        Quick, Listening -> null
        is Grammar -> jlpt
        is Reading -> jlpt
    }

    /** Only a checkpoint is passed or failed. */
    fun passed(correct: Int, total: Int): Boolean? =
        if (this is Checkpoint) total > 0 && correct.toDouble() / total >= CHECKPOINT_PASS_FRACTION else null

    /** The number of questions is not known up front. */
    val adaptive: Boolean get() = this == Placement

    companion object {
        fun decode(value: String): QuizKind = when {
            value == "placement" -> Placement
            value == "listening" -> Listening
            value.startsWith("checkpoint-") -> Checkpoint(value.removePrefix("checkpoint-").toInt())
            value.startsWith("grammar-") -> value.removePrefix("grammar-").split("-").let { Grammar(it[0].toInt(), it[1].toInt()) }
            value.startsWith("reading-") -> Reading(value.removePrefix("reading-").toInt())
            else -> Quick
        }
    }
}

/** What a [QuizRun] needs from the rest of the app: questions, and places to put the outcome. */
interface QuizBackend {
    /**
     * The questions of [kind]. [profileLevel] is the learner's level (quick and listening quizzes);
     * [stageLevel] is the JLPT number the adaptive placement test is asking about right now.
     */
    suspend fun questions(uid: String, kind: QuizKind, profileLevel: JlptLevel, stageLevel: Int): List<Question>

    suspend fun recordGrammar(uid: String, grammarId: Int, correct: Boolean, at: Long)
    suspend fun saveResult(result: TestResultEntity)
    suspend fun addWordsToFlashcards(uid: String, wordIds: List<Int>, now: Instant)
    suspend fun saveProfile(uid: String, profile: StudyProfile)
}

/** [QuizBackend] on the app's repositories. */
class RepositoryQuizBackend @Inject constructor(
    private val quizzes: QuizRepository,
    private val grammar: GrammarRepository,
    private val reading: ReadingRepository,
    private val study: StudyRepository,
    private val profiles: StudyProfileRepository,
) : QuizBackend {
    override suspend fun questions(uid: String, kind: QuizKind, profileLevel: JlptLevel, stageLevel: Int): List<Question> = when (kind) {
        QuizKind.Placement -> quizzes.placementStage(stageLevel, STAGE_SIZE)
        is QuizKind.Checkpoint -> quizzes.checkpoint(kind.jlpt)
        QuizKind.Quick -> quizzes.quick(uid, profileLevel)
        QuizKind.Listening -> quizzes.listening(uid, profileLevel)
        is QuizKind.Reading -> reading.questions(kind.jlpt)
        is QuizKind.Grammar -> grammar.practice(uid, kind.jlpt, kind.pointId.takeIf { it != 0 })
    }

    override suspend fun recordGrammar(uid: String, grammarId: Int, correct: Boolean, at: Long) = grammar.record(uid, grammarId, correct, at)
    override suspend fun saveResult(result: TestResultEntity) = quizzes.save(result)
    override suspend fun addWordsToFlashcards(uid: String, wordIds: List<Int>, now: Instant) =
        wordIds.forEach { study.addToStudy(uid, ItemRef(ItemType.Word, it), now) }

    override suspend fun saveProfile(uid: String, profile: StudyProfile) = profiles.save(uid, profile)

    private companion object {
        const val STAGE_SIZE = 5
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

data class QuizRunState(
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

/**
 * One test taken by one learner: building the questions, answering them, the adaptive placement
 * stages, scoring, saving the result, and what can be done with it afterwards (add missed words to
 * the flashcards, adopt the placement level). Everything on screen is in [state]; speech and the
 * screen's lifecycle stay outside. Call [start] once.
 */
class QuizRun(
    private val uid: String,
    private val kind: QuizKind,
    private val profile: StudyProfile?,
    private val backend: QuizBackend,
    private val clock: StudyClock,
    private val now: () -> Instant = Instant::now,
) {
    private val _state = MutableStateFlow(QuizRunState(title = kind.title))
    val state: StateFlow<QuizRunState> = _state.asStateFlow()

    private val queue = ArrayDeque<Question>()
    private val answered = mutableListOf<Pair<Question, Boolean>>()
    private val placement = PlacementTest()
    private var advancing = false

    suspend fun start() {
        val questions = backend.questions(uid, kind, profile?.level ?: JlptLevel.N5, placement.level)
        if (questions.isEmpty()) {
            _state.update { it.copy(loading = false, error = "Not enough material to build this test yet.") }
            return
        }
        queue.addAll(questions)
        clock.start()
        showNext(total = if (kind.adaptive) null else questions.size)
    }

    suspend fun choose(index: Int) {
        val current = _state.value.question ?: return
        if (_state.value.selected != null) return
        val correct = index == current.answer
        answered += current to correct
        if (kind.adaptive) placement.answer(correct)
        clock.tap(reviews = 1)
        _state.update { it.copy(selected = index) }
        current.grammarId?.let { backend.recordGrammar(uid, it, correct, now().toEpochMilli()) }
    }

    suspend fun next() {
        if (_state.value.selected == null || advancing) return
        advancing = true // a second tap while the result saves must not finish the test twice
        clock.tap()
        if (queue.isNotEmpty()) return showNext(_state.value.total)
        if (kind.adaptive && !placement.finished) {
            _state.update { it.copy(loading = true) }
            queue.addAll(backend.questions(uid, kind, profile?.level ?: JlptLevel.N5, placement.level))
            if (queue.isNotEmpty()) return showNext(null)
        }
        finish()
    }

    /** Puts the words that were missed into the flashcards, as new cards for today. */
    suspend fun addMissedToFlashcards() {
        val result = _state.value.result ?: return
        backend.addWordsToFlashcards(uid, result.missedWordIds, now())
        _state.update { it.copy(result = result.copy(missedAdded = true)) }
    }

    /** Placement only: make the placement result the learner's study level. */
    suspend fun applyPlacementLevel() {
        val current = profile ?: return
        val result = _state.value.result ?: return
        val level = result.placementLevel ?: return
        backend.saveProfile(uid, current.copy(level = level))
        _state.update { it.copy(result = result.copy(levelApplied = true)) }
    }

    fun pause() = clock.pause()

    fun resume() = clock.resume()

    private fun showNext(total: Int?) {
        advancing = false
        val question = queue.removeFirst()
        _state.update {
            it.copy(loading = false, question = question, number = answered.size + 1, total = total, selected = null)
        }
    }

    private suspend fun finish() {
        val correct = answered.count { it.second }
        val total = answered.size
        val missed = answered.filter { !it.second }.mapNotNull { it.first.wordId }.distinct()
        val placementLevel = if (kind.adaptive) placement.result else null
        backend.saveResult(
            TestResultEntity(
                uid = uid, type = kind.testType, level = kind.savedLevel(placementLevel),
                correct = correct, total = total, takenAt = now().toEpochMilli(),
            ),
        )
        clock.finish()
        _state.update {
            it.copy(
                loading = false,
                question = null,
                result = QuizResult(
                    title = kind.title, correct = correct, total = total, passed = kind.passed(correct, total),
                    placementLevel = placementLevel, missedWordIds = missed, currentLevel = profile?.level,
                ),
            )
        }
    }
}
