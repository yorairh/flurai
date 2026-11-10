package com.flurai.app.ui.study

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.flurai.app.core.activity.StudyActivityRepository
import com.flurai.app.core.activity.StudyClock
import com.flurai.app.core.auth.AuthRepository
import com.flurai.app.core.auth.uid
import com.flurai.app.core.profile.StudyProfileRepository
import com.flurai.app.core.speech.JapaneseSpeaker
import com.flurai.app.core.srs.Rating
import com.flurai.app.core.study.CardContentSource
import com.flurai.app.core.study.CardKind
import com.flurai.app.core.study.SessionState
import com.flurai.app.core.study.StudyRepository
import com.flurai.app.core.study.StudySession
import com.flurai.app.core.study.StudySource
import com.flurai.app.ui.StudyRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Connects the flashcard screen to a [StudySession]: the route, the learner's profile, the study
 * clock's lifecycle, and speech. The session holds everything else.
 */
@HiltViewModel
class ReviewViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val auth: AuthRepository,
    private val profiles: StudyProfileRepository,
    private val repository: StudyRepository,
    private val activity: StudyActivityRepository,
    private val content: CardContentSource,
    val speaker: JapaneseSpeaker,
) : ViewModel() {

    private val source = StudySource.decode(savedStateHandle.toRoute<StudyRoute>().source)
    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private var session: StudySession? = null

    init {
        speaker.start()
        viewModelScope.launch {
            val uid = auth.uid ?: return@launch
            val profile = profiles.profile(uid).first() ?: return@launch
            val session = StudySession(uid, profile, source, repository, content, StudyClock(activity) { auth.uid })
            this@ReviewViewModel.session = session
            launch { follow(session) }
            session.start()
        }
    }

    /** Mirrors the session's state and plays a Listening card's audio as it appears: hearing the word is the question. */
    private suspend fun follow(session: StudySession) {
        var spoken: Any? = null
        session.state.collect { s ->
            _state.value = s
            val card = s.current
            if (card == null) {
                spoken = null
            } else if (card !== spoken) {
                spoken = card
                if (card.item.kind == CardKind.Listening) speaker.speak(card.content.speech)
            }
        }
    }

    /** Audio plays with the answer, never before: hearing a word gives its reading away. */
    fun reveal() {
        session?.reveal()
        speakCurrent()
    }

    fun answer(rating: Rating) = run { answer(rating) }

    /** Hide this word until tomorrow. */
    fun buryCurrent() = run { buryCurrent() }

    /** Stop showing this word until it's resumed from its word page. */
    fun suspendCurrent() = run { suspendCurrent() }

    fun speakCurrent() {
        _state.value.current?.let { speaker.speak(it.content.speech) }
    }

    /** After finishing: keep going with more new words from [from] (default: the same deck). */
    fun learnMore(from: StudySource = source) = run { learnMore(from) }

    /** Called when the screen comes back into view, so learning cards that became due show up. */
    fun refresh() = run { refresh() }

    fun onPause() {
        session?.pause()
    }

    fun onResume() {
        session?.resume()
    }

    private fun run(action: suspend StudySession.() -> Unit) {
        val session = session ?: return
        viewModelScope.launch { session.action() }
    }
}
