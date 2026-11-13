package com.flurai.app.ui.explore

import com.flurai.app.core.study.cleanDeckName
import com.flurai.app.core.study.DeckRepository
import com.flurai.app.core.auth.uid
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.flurai.app.core.auth.AuthRepository
import com.flurai.app.core.media.Deck
import com.flurai.app.core.media.MediaAnalysis
import com.flurai.app.core.media.MediaRepository
import com.flurai.app.core.media.MediaType
import com.flurai.app.core.model.JlptLevel
import com.flurai.app.core.profile.StudyProfileRepository
import com.flurai.app.ui.MediaDetailRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MediaListState(
    val type: MediaType = MediaType.Anime,
    val level: JlptLevel = JlptLevel.N5,
    val decks: List<Deck> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val hasMore: Boolean = false,
)

@HiltViewModel
class MediaViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val profiles: StudyProfileRepository,
    private val media: MediaRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(MediaListState())
    val state: StateFlow<MediaListState> = _state

    init {
        viewModelScope.launch {
            val level = auth.uid?.let { profiles.profile(it).first()?.level } ?: JlptLevel.N5
            _state.update { it.copy(level = level) }
            load(reset = true)
        }
    }

    fun chooseType(type: MediaType) {
        _state.update { it.copy(type = type) }
        viewModelScope.launch { load(reset = true) }
    }

    fun chooseLevel(level: JlptLevel) {
        _state.update { it.copy(level = level) }
        viewModelScope.launch { load(reset = true) }
    }

    fun loadMore() {
        if (_state.value.loading || !_state.value.hasMore) return
        viewModelScope.launch { load(reset = false) }
    }

    fun retry() {
        viewModelScope.launch { load(reset = _state.value.decks.isEmpty()) }
    }

    private suspend fun load(reset: Boolean) {
        val current = _state.value
        val offset = if (reset) 0 else current.decks.size
        _state.update { it.copy(loading = true, error = null, decks = if (reset) emptyList() else it.decks) }
        try {
            val page = media.page(current.type, current.level, offset)
            _state.update {
                // Ignore an answer that arrived after the learner switched type or level.
                if (it.type != current.type || it.level != current.level) it else {
                    val all = it.decks + page.data
                    it.copy(loading = false, decks = all, hasMore = page.data.isNotEmpty() && all.size < page.totalItems)
                }
            }
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = e.message ?: "Something went wrong.") }
        }
    }
}

data class MediaDetailState(
    val deck: Deck? = null,
    val loading: Boolean = true,
    val analysis: MediaAnalysis? = null,
    val error: String? = null,
    val added: Int? = null,
    /** Name of the deck the top words were saved into. */
    val savedDeck: String? = null,
    /** How many words the saved deck holds. */
    val savedDeckWords: Int = 0,
    /** The title's whole word list is being fetched for a deck. */
    val savingAll: Boolean = false,
    val saveAllError: String? = null,
    val includeHarder: Boolean = false,
)

@HiltViewModel
class MediaDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val auth: AuthRepository,
    private val media: MediaRepository,
    private val decks: DeckRepository,
    private val profiles: StudyProfileRepository,
) : ViewModel() {
    private val deckId = savedStateHandle.toRoute<MediaDetailRoute>().deckId
    private val _state = MutableStateFlow(MediaDetailState(deck = media.deck(deckId)))
    val state: StateFlow<MediaDetailState> = _state

    init {
        load()
    }

    fun load() {
        val uid = auth.uid
        if (uid == null || _state.value.deck == null) {
            _state.update { it.copy(loading = false) }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val level = profiles.profile(uid).first()?.level ?: JlptLevel.N5
                val analysis = media.analyse(uid, deckId, level, _state.value.includeHarder)
                _state.update { it.copy(loading = false, analysis = analysis) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Something went wrong.") }
            }
        }
    }

    fun setIncludeHarder(include: Boolean) {
        _state.update { it.copy(includeHarder = include) }
        load()
    }

    /** Saves the top words as one of the learner's decks, named after the title, without starting any cards. */
    fun saveAsDeck() {
        val uid = auth.uid ?: return
        val words = _state.value.analysis?.learnable?.map { it.id }?.takeIf { it.isNotEmpty() } ?: return
        val deck = _state.value.deck ?: return
        val name = "Words: " + (deck.englishTitle?.takeIf { it.isNotBlank() } ?: deck.originalTitle)
        viewModelScope.launch {
            decks.create(uid, name, words) ?: return@launch
            _state.update { it.copy(savedDeck = cleanDeckName(name), savedDeckWords = words.size) }
        }
    }

    /** Saves every word of the title that the app teaches as a deck, most common first, known words included. */
    fun saveAllAsDeck() {
        val uid = auth.uid ?: return
        val deck = _state.value.deck ?: return
        if (_state.value.savingAll) return
        val name = "All words: " + (deck.englishTitle?.takeIf { it.isNotBlank() } ?: deck.originalTitle)
        _state.update { it.copy(savingAll = true, saveAllError = null) }
        viewModelScope.launch {
            try {
                val words = media.allWordIds(deckId)
                if (words.isEmpty()) {
                    _state.update { it.copy(savingAll = false, saveAllError = "None of this title's words are in the app's word lists.") }
                    return@launch
                }
                decks.create(uid, name, words)
                _state.update { it.copy(savingAll = false, savedDeck = cleanDeckName(name), savedDeckWords = words.size) }
            } catch (e: Exception) {
                _state.update { it.copy(savingAll = false, saveAllError = e.message ?: "Something went wrong.") }
            }
        }
    }

    fun addWords() {
        val uid = auth.uid ?: return
        val words = _state.value.analysis?.learnable?.map { it.id } ?: return
        viewModelScope.launch {
            media.addToFlashcards(uid, words)
            _state.update { it.copy(added = words.size, analysis = it.analysis?.copy(learnable = emptyList())) }
        }
    }
}
