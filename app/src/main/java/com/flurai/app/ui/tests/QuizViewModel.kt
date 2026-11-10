package com.flurai.app.ui.tests

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.flurai.app.core.activity.StudyActivityRepository
import com.flurai.app.core.activity.StudyClock
import com.flurai.app.core.auth.AuthRepository
import com.flurai.app.core.auth.uid
import com.flurai.app.core.profile.StudyProfileRepository
import com.flurai.app.core.quiz.QuestionKind
import com.flurai.app.core.quiz.QuizBackend
import com.flurai.app.core.quiz.QuizKind
import com.flurai.app.core.quiz.QuizRun
import com.flurai.app.core.quiz.QuizRunState
import com.flurai.app.core.speech.JapaneseSpeaker
import com.flurai.app.ui.QuizRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Connects the quiz screen to a [QuizRun]: the route, the learner's profile, the study clock's
 * lifecycle, and the audio of listening questions. The run holds everything else.
 */
@HiltViewModel
class QuizViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val auth: AuthRepository,
    private val profiles: StudyProfileRepository,
    private val backend: QuizBackend,
    private val activity: StudyActivityRepository,
    val speaker: JapaneseSpeaker,
) : ViewModel() {

    private val kind = QuizKind.decode(savedStateHandle.toRoute<QuizRoute>().kind)
    private val _state = MutableStateFlow(QuizRunState(title = kind.title))
    val state: StateFlow<QuizRunState> = _state.asStateFlow()

    private var run: QuizRun? = null

    init {
        if (kind == QuizKind.Listening) speaker.start()
        viewModelScope.launch {
            val uid = auth.uid ?: return@launch
            val run = QuizRun(uid, kind, profiles.profile(uid).first(), backend, StudyClock(activity) { auth.uid })
            this@QuizViewModel.run = run
            launch { follow(run) }
            run.start()
        }
    }

    /** Mirrors the run's state and says a listening question's word as the question appears. */
    private suspend fun follow(run: QuizRun) {
        var spoken: Any? = null
        run.state.collect { s ->
            _state.value = s
            val question = s.question
            if (question == null) {
                spoken = null
            } else if (question !== spoken) {
                spoken = question
                if (question.kind == QuestionKind.WordListening) speaker.speak(question.prompt)
            }
        }
    }

    fun choose(index: Int) = launch { choose(index) }

    fun next() = launch { next() }

    fun replay() {
        _state.value.question?.let { speaker.speak(it.prompt) }
    }

    fun addMissedToFlashcards() = launch { addMissedToFlashcards() }

    fun applyPlacementLevel() = launch { applyPlacementLevel() }

    fun onPause() {
        run?.pause()
    }

    fun onResume() {
        run?.resume()
    }

    private fun launch(action: suspend QuizRun.() -> Unit) {
        val run = run ?: return
        viewModelScope.launch { run.action() }
    }
}
