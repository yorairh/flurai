package com.flurai.app.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.flurai.app.core.activity.ActivityTimer
import com.flurai.app.core.activity.StudyActivityRepository
import com.flurai.app.core.auth.AuthRepository
import com.flurai.app.core.chat.AiTurn
import com.flurai.app.core.chat.ChatRepository
import com.flurai.app.core.chat.ChatSession
import com.flurai.app.core.chat.Scenario
import com.flurai.app.core.chat.scenario
import com.flurai.app.core.model.JlptLevel
import com.flurai.app.core.profile.StudyProfileRepository
import com.flurai.app.core.speech.JapaneseSpeaker
import com.flurai.app.ui.ChatRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

sealed interface ChatMessage {
    /** [correction] is filled in when the tutor's next turn arrives. */
    data class Learner(val text: String, val correction: String? = null) : ChatMessage
    data class Tutor(val turn: AiTurn, val showTranslation: Boolean = false) : ChatMessage
}

data class ChatUiState(
    val scenario: Scenario,
    val messages: List<ChatMessage> = emptyList(),
    /** Waiting for the tutor. */
    val sending: Boolean = true,
    val error: String? = null,
    /** The message that failed, kept so it can be sent again. */
    val failedText: String? = null,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val auth: AuthRepository,
    private val profiles: StudyProfileRepository,
    private val chats: ChatRepository,
    private val activity: StudyActivityRepository,
    private val speaker: JapaneseSpeaker,
) : ViewModel() {
    private val _state = MutableStateFlow(ChatUiState(scenario(savedStateHandle.toRoute<ChatRoute>().scenarioId)))
    val state: StateFlow<ChatUiState> = _state

    private var session: ChatSession? = null
    private val timer = ActivityTimer()

    init {
        speaker.start()
        open()
    }

    /** Starts the conversation: the tutor opens the scenario. */
    fun open() {
        val uid = auth.currentUser.value?.uid ?: return
        _state.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            try {
                val level = profiles.profile(uid).first()?.level ?: JlptLevel.N5
                val context = chats.learnerContext(uid, level)
                session = chats.start(context, _state.value.scenario)
                val turn = session!!.send(START)
                timer.start()
                _state.update { it.copy(sending = false, messages = listOf(ChatMessage.Tutor(turn))) }
            } catch (e: Exception) {
                _state.update { it.copy(sending = false, error = explain(e)) }
            }
        }
    }

    fun send(text: String) {
        val message = text.trim()
        val current = session
        if (message.isEmpty() || _state.value.sending || current == null) return
        _state.update { it.copy(sending = true, error = null, failedText = null, messages = it.messages + ChatMessage.Learner(message)) }
        viewModelScope.launch {
            try {
                val turn = current.send(message)
                credit()
                _state.update { state ->
                    val messages = state.messages.toMutableList()
                    // The correction belongs to the learner's message it answers.
                    val index = messages.indexOfLast { it is ChatMessage.Learner }
                    if (index >= 0) messages[index] = (messages[index] as ChatMessage.Learner).copy(correction = turn.correction)
                    state.copy(sending = false, messages = messages + ChatMessage.Tutor(turn))
                }
            } catch (e: Exception) {
                // Take the message back out so it can be sent again without a duplicate.
                _state.update { state ->
                    state.copy(sending = false, error = explain(e), failedText = message, messages = state.messages.dropLast(1))
                }
            }
        }
    }

    fun toggleTranslation(index: Int) = _state.update { state ->
        state.copy(messages = state.messages.mapIndexed { i, m -> if (i == index && m is ChatMessage.Tutor) m.copy(showTranslation = !m.showTranslation) else m })
    }

    fun speak(text: String) = speaker.speak(text)

    private fun credit() {
        val uid = auth.currentUser.value?.uid ?: return
        activity.record(uid, Instant.now(), timer.tick(), 1)
    }

    fun onPause() {
        val uid = auth.currentUser.value?.uid ?: return
        activity.record(uid, Instant.now(), timer.stop(), 0)
    }

    fun onResume() = timer.start()

    private fun explain(e: Exception): String {
        val text = (e.message ?: "") + " " + e.javaClass.simpleName
        return when {
            text.contains("quota", true) || text.contains("429") || text.contains("exhausted", true) ->
                "The free AI limit was reached for now. Try again in a few minutes."
            text.contains("403") || text.contains("permission", true) || text.contains("not enabled", true) ||
                text.contains("API has not been used", true) || text.contains("App Check", true) || text.contains("unauthenticated", true) ->
                "The AI is not switched on for this app yet. Set up Firebase AI Logic in the Firebase console (see the release notes), then try again."
            text.contains("not found", true) || text.contains("404") -> "The AI model was not found. The app may need an update."
            text.contains("Unable to resolve host", true) || text.contains("network", true) || text.contains("UnknownHost", true) ->
                "No internet connection. Check your connection and try again."
            else -> "The tutor could not answer: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private companion object {
        const val START = "START"
    }
}
