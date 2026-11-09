package com.flurai.app.ui.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flurai.app.core.auth.AuthRepository
import com.flurai.app.core.content.GrammarPointEntity
import com.flurai.app.core.grammar.GrammarRepository
import com.flurai.app.core.profile.StudyProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VideosUiState(
    val jlpt: Int = 5,
    /** Grammar points the learner has got wrong more often than right, worst first. */
    val weakGrammar: List<GrammarPointEntity> = emptyList(),
)

@HiltViewModel
class VideosViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val profiles: StudyProfileRepository,
    grammar: GrammarRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(VideosUiState())
    val state: StateFlow<VideosUiState> = _state

    init {
        val uid = auth.currentUser.value?.uid
        viewModelScope.launch {
            val level = uid?.let { profiles.profile(it).first()?.level?.jlpt } ?: 5
            _state.update { it.copy(jlpt = level) }
        }
        viewModelScope.launch {
            val progress = if (uid == null) flowOf(emptyList()) else grammar.progress(uid)
            combine(grammar.points(), progress) { points, progress ->
                val byId = progress.associateBy { it.grammarId }
                points.mapNotNull { p -> byId[p.id]?.takeIf { it.wrong > it.correct }?.let { p to it.wrong - it.correct } }
                    .sortedByDescending { it.second }.map { it.first }.take(5)
            }.collect { weak -> _state.update { it.copy(weakGrammar = weak) } }
        }
    }

    fun chooseLevel(jlpt: Int) = _state.update { it.copy(jlpt = jlpt) }
}
