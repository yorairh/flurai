package com.flurai.app.ui.reading

import com.flurai.app.core.auth.uid
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flurai.app.core.auth.AuthRepository
import com.flurai.app.core.content.WordEntity
import com.flurai.app.core.profile.StudyProfileRepository
import com.flurai.app.core.reading.Lexicon
import com.flurai.app.core.reading.ReadingRepository
import com.flurai.app.core.reading.Token
import com.flurai.app.core.study.ItemRef
import com.flurai.app.core.study.ItemType
import com.flurai.app.core.study.StudyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

data class ReadLine(val tokens: List<Token>, val english: String)

data class ReadingUiState(
    val jlpt: Int = 5,
    val loading: Boolean = true,
    val lines: List<ReadLine> = emptyList(),
    val showTranslations: Boolean = false,
    val selected: WordEntity? = null,
    val addedWordIds: Set<Int> = emptySet(),
)

@HiltViewModel
class ReadingViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val profiles: StudyProfileRepository,
    private val reading: ReadingRepository,
    private val study: StudyRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ReadingUiState())
    val state: StateFlow<ReadingUiState> = _state

    init {
        viewModelScope.launch {
            val uid = auth.uid
            val start = uid?.let { profiles.profile(it).first()?.level?.jlpt } ?: 5
            load(start)
        }
    }

    fun chooseLevel(jlpt: Int) = load(jlpt)

    fun newSet() = load(_state.value.jlpt)

    private fun load(jlpt: Int) {
        _state.update { it.copy(jlpt = jlpt, loading = true, selected = null) }
        viewModelScope.launch {
            val lexicon: Lexicon = reading.lexicon()
            val set = reading.set(jlpt)
            _state.update {
                it.copy(
                    loading = false,
                    lines = set.sentences.map { s -> ReadLine(lexicon.tokenize(s.japanese), s.english) },
                )
            }
        }
    }

    fun toggleTranslations() = _state.update { it.copy(showTranslations = !it.showTranslations) }

    fun select(word: WordEntity?) = _state.update { it.copy(selected = word) }

    fun addToFlashcards(word: WordEntity) {
        val uid = auth.uid ?: return
        viewModelScope.launch {
            study.addToStudy(uid, ItemRef(ItemType.Word, word.id), Instant.now())
            _state.update { it.copy(addedWordIds = it.addedWordIds + word.id) }
        }
    }
}
