package com.flurai.app.ui.grammar

import kotlinx.coroutines.flow.first
import com.flurai.app.core.profile.StudyProfileRepository
import com.flurai.app.core.auth.uid
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.flurai.app.core.activity.StudyActivityRepository
import com.flurai.app.core.activity.StudyClock
import com.flurai.app.core.auth.AuthRepository
import com.flurai.app.core.content.GrammarExampleEntity
import com.flurai.app.core.content.GrammarPointEntity
import com.flurai.app.core.grammar.GrammarProgressEntity
import com.flurai.app.core.grammar.GrammarRepository
import com.flurai.app.core.speech.JapaneseSpeaker
import com.flurai.app.core.grammar.GrammarStatus
import com.flurai.app.core.grammar.KNOWN_AFTER_CORRECT
import com.flurai.app.core.grammar.grammarStatus
import com.flurai.app.core.grammar.lessonOrder
import com.flurai.app.core.grammar.nextLesson
import com.flurai.app.ui.GrammarRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PointItem(val point: GrammarPointEntity, val status: GrammarStatus)

/** One level's points in lesson order. */
data class GrammarLevel(val jlpt: Int, val points: List<PointItem>) {
    val known: Int get() = points.count { it.status == GrammarStatus.Known }
    val learning: Int get() = points.count { it.status == GrammarStatus.Learning }

    /** Points the learner has started: what practice asks about. */
    val started: Int get() = known + learning

    /** The lesson to take next: the first point not started yet. Null when every point is. */
    val next: GrammarPointEntity? get() = points.firstOrNull { it.status == GrammarStatus.New }?.point
}

data class GrammarListState(val levels: List<GrammarLevel> = emptyList())

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GrammarListViewModel @Inject constructor(
    auth: AuthRepository,
    grammar: GrammarRepository,
    profiles: StudyProfileRepository,
) : ViewModel() {
    private val _selected = MutableStateFlow(5)

    /** The JLPT level shown in the list. Starts at the learner's own level. */
    val selected: StateFlow<Int> = _selected

    private var chosen = false

    init {
        viewModelScope.launch {
            val uid = auth.uid ?: return@launch
            val level = profiles.profile(uid).first()?.level?.jlpt ?: return@launch
            if (!chosen) _selected.value = level
        }
    }

    fun select(jlpt: Int) {
        chosen = true
        _selected.value = jlpt
    }

    val state: StateFlow<GrammarListState> = auth.currentUser.flatMapLatest { user ->
        val progress = if (user == null) flowOf(emptyList()) else grammar.progress(user.uid)
        combine(grammar.points(), progress) { points, progress ->
            val byId = progress.associateBy { it.grammarId }
            GrammarListState(
                levels = lessonOrder(points).groupBy { it.jlpt }.map { (jlpt, list) ->
                    GrammarLevel(jlpt, list.map { PointItem(it, grammarStatus(byId[it.id])) })
                },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GrammarListState())
}

data class GrammarDetailState(
    val point: GrammarPointEntity? = null,
    val examples: List<GrammarExampleEntity> = emptyList(),
    val progressText: String? = null,
    /** Null until the learner's progress is read. */
    val status: GrammarStatus? = null,
    /** The next lesson of this level that is not started yet (never this point). */
    val next: GrammarPointEntity? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GrammarDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val auth: AuthRepository,
    private val grammar: GrammarRepository,
    activity: StudyActivityRepository,
    val speaker: JapaneseSpeaker,
) : ViewModel() {
    /** Reading a grammar point counts as study time. */
    val clock = StudyClock(activity, { auth.uid })

    private val id = savedStateHandle.toRoute<GrammarRoute>().id
    private val _state = MutableStateFlow(GrammarDetailState())
    val state: StateFlow<GrammarDetailState> = _state

    fun speak(text: String, slow: Boolean) = speaker.speak(text, slow)

    init {
        clock.start()
        speaker.start()
        viewModelScope.launch {
            _state.update { it.copy(point = grammar.point(id), examples = grammar.examples(id)) }
        }
        viewModelScope.launch {
            val uid = auth.uid ?: return@launch
            combine(grammar.points(), grammar.progress(uid)) { points, all ->
                val mine = all.firstOrNull { p -> p.grammarId == id }
                val jlpt = points.firstOrNull { it.id == id }?.jlpt
                val next = jlpt?.let { nextLesson(points, all.mapTo(HashSet()) { p -> p.grammarId } + id, it) }
                mine to next
            }.collect { (mine, next) ->
                _state.update { it.copy(progressText = progressText(mine), status = grammarStatus(mine), next = next) }
            }
        }
    }

    /** "I've learned this": the point joins the learner's practice. */
    fun markLearned() {
        val uid = auth.uid ?: return
        clock.tap()
        viewModelScope.launch { grammar.markLearned(uid, id, System.currentTimeMillis()) }
    }

    private fun progressText(progress: GrammarProgressEntity?): String? = when (grammarStatus(progress)) {
        GrammarStatus.New -> "Not learned yet. Read the lesson, then mark it learned or try a question: it joins your practice."
        GrammarStatus.Learning ->
            if ((progress?.correct ?: 0) + (progress?.wrong ?: 0) == 0) {
                "Learned. It is in your practice now: answer $KNOWN_AFTER_CORRECT right to make it known."
            } else {
                "Learned: ${progress?.correct ?: 0} right, ${progress?.wrong ?: 0} wrong in practice. Answer $KNOWN_AFTER_CORRECT right (the latest one included) to make it known."
            }
        GrammarStatus.Known -> "Known: ${progress?.correct ?: 0} right answers."
    }
}
