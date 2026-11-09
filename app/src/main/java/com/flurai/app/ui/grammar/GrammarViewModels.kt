package com.flurai.app.ui.grammar

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.flurai.app.core.auth.AuthRepository
import com.flurai.app.core.content.GrammarExampleEntity
import com.flurai.app.core.content.GrammarPointEntity
import com.flurai.app.core.grammar.GrammarProgressEntity
import com.flurai.app.core.grammar.GrammarRepository
import com.flurai.app.core.grammar.GrammarStatus
import com.flurai.app.core.grammar.KNOWN_AFTER_CORRECT
import com.flurai.app.core.grammar.grammarStatus
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

data class GrammarLevel(val jlpt: Int, val points: List<PointItem>) {
    val known: Int get() = points.count { it.status == GrammarStatus.Known }
    val learning: Int get() = points.count { it.status == GrammarStatus.Learning }
}

data class GrammarListState(val levels: List<GrammarLevel> = emptyList())

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GrammarListViewModel @Inject constructor(auth: AuthRepository, grammar: GrammarRepository) : ViewModel() {
    val state: StateFlow<GrammarListState> = auth.currentUser.flatMapLatest { user ->
        val progress = if (user == null) flowOf(emptyList()) else grammar.progress(user.uid)
        combine(grammar.points(), progress) { points, progress ->
            val byId = progress.associateBy { it.grammarId }
            GrammarListState(
                levels = points.groupBy { it.jlpt }.toSortedMap(compareByDescending { it }).map { (jlpt, list) ->
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
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GrammarDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val auth: AuthRepository,
    private val grammar: GrammarRepository,
) : ViewModel() {
    private val id = savedStateHandle.toRoute<GrammarRoute>().id
    private val _state = MutableStateFlow(GrammarDetailState())
    val state: StateFlow<GrammarDetailState> = _state

    init {
        viewModelScope.launch {
            _state.update { it.copy(point = grammar.point(id), examples = grammar.examples(id)) }
        }
        viewModelScope.launch {
            val uid = auth.currentUser.value?.uid ?: return@launch
            grammar.progress(uid).collect { all ->
                _state.update { it.copy(progressText = progressText(all.firstOrNull { p -> p.grammarId == id })) }
            }
        }
    }

    private fun progressText(progress: GrammarProgressEntity?): String? = when (grammarStatus(progress)) {
        GrammarStatus.New -> "Not practised yet."
        GrammarStatus.Learning ->
            "${progress?.correct ?: 0} right, ${progress?.wrong ?: 0} wrong so far. Answer $KNOWN_AFTER_CORRECT right (the latest one included) to mark it known."
        GrammarStatus.Known -> "Known: ${progress?.correct ?: 0} right answers."
    }
}
