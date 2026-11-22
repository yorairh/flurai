package com.flurai.app.ui.explore

import com.flurai.app.core.media.ImportOptions
import com.flurai.app.core.media.pickTitleWords
import com.flurai.app.core.study.DeckRepository
import com.flurai.app.core.study.ImportedDeck
import com.flurai.app.core.study.jitenSource
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
    /** The learner's study level: the "too hard" filter and its label follow it. */
    val studyLevel: JlptLevel = JlptLevel.N5,
    val options: ImportOptions = ImportOptions(),
    /** Take the daily new words from the deck once it is made ("Studying from"). */
    val studyFromDeck: Boolean = false,
    /** The deck made from this title before, if any (importing again adds the missing words to it). */
    val existingDeck: String? = null,
    val importing: Boolean = false,
    val imported: ImportedDeck? = null,
    val studyingFromImported: Boolean = false,
) {
    /** The words the sheet's choices would put in the deck, in deck order. */
    val picked: List<Int> by lazy {
        analysis?.let { pickTitleWords(it.words, it.totalOccurrences, options, studyLevel) }.orEmpty()
    }
}

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
                val existing = decks.bySource(uid, jitenSource(deckId))?.name
                val analysis = media.analyse(uid, deckId)
                _state.update { it.copy(loading = false, analysis = analysis, studyLevel = level, existingDeck = existing) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Something went wrong.") }
            }
        }
    }

    fun setOptions(options: ImportOptions) = _state.update { it.copy(options = options) }

    fun setStudyFromDeck(on: Boolean) = _state.update { it.copy(studyFromDeck = on) }

    /**
     * Makes the deck from the sheet's choices (or adds the missing words to the deck made from this title
     * before), without starting any cards. Optionally makes it the deck new words come from.
     */
    fun importDeck() {
        val uid = auth.uid ?: return
        val s = _state.value
        val deck = s.deck ?: return
        val words = s.picked
        if (words.isEmpty() || s.importing) return
        _state.update { it.copy(importing = true) }
        viewModelScope.launch {
            val result = decks.importInto(uid, jitenSource(deckId), deck.englishTitle?.takeIf { it.isNotBlank() } ?: deck.originalTitle, words)
            if (s.studyFromDeck) {
                profiles.profile(uid).first()?.let { profiles.save(uid, it.copy(studyDeckKey = result.key)) }
            }
            _state.update { it.copy(importing = false, imported = result, existingDeck = result.name, studyingFromImported = s.studyFromDeck) }
        }
    }

    fun dismissImported() = _state.update { it.copy(imported = null) }
}
