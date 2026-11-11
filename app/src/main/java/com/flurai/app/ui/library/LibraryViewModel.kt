package com.flurai.app.ui.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.flurai.app.core.content.ContentDao
import com.flurai.app.core.content.KanjiEntity
import com.flurai.app.core.content.Romaji
import com.flurai.app.core.content.SearchQuery
import com.flurai.app.core.content.WordEntity
import com.flurai.app.core.content.rankKanjiList
import com.flurai.app.core.content.rankWords
import com.flurai.app.core.study.DeckRepository
import com.flurai.app.ui.LibraryRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LibraryTab { Words, Kanji }

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val content: ContentDao,
    private val decks: DeckRepository,
) : ViewModel() {

    /** Not 0 when the library was opened to put words into one of the learner's decks. */
    val pickDeckId: Long = savedStateHandle.toRoute<LibraryRoute>().deckId

    private val _tab = MutableStateFlow(LibraryTab.Words)
    val tab: StateFlow<LibraryTab> = _tab.asStateFlow()

    private val _jlpt = MutableStateFlow(5)
    val jlpt: StateFlow<Int> = _jlpt.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val wordCounts: StateFlow<Map<Int, Int>> = content.wordCountsByLevel()
        .map { counts -> counts.associate { it.jlpt to it.count } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val kanjiCounts: StateFlow<Map<Int, Int>> = content.kanjiCountsByLevel()
        .map { counts -> counts.associate { it.jlpt to it.count } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val search = _query.debounce(200).map { SearchQuery.of(it) }

    /** Null while loading. A search covers every level, best match first; otherwise the selected level is listed. */
    val words: StateFlow<List<WordEntity>?> = combine(_jlpt, search) { jlpt, query -> jlpt to query }
        .flatMapLatest { (jlpt, query) ->
            if (query.text.isEmpty()) content.wordsAtLevel(jlpt)
            else content.searchCandidates(query.text, query.kana).map { rankWords(it, query) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val kanji: StateFlow<List<KanjiEntity>?> = combine(_jlpt, search) { jlpt, query -> jlpt to query }
        .flatMapLatest { (jlpt, query) ->
            if (query.text.isEmpty()) content.kanjiAtLevel(jlpt)
            else content.searchKanji(query.text, query.kana, Romaji.toKatakana(query.kana)).map { rankKanjiList(it, query) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Ids of the words already in the deck being filled (empty when the library is not picking words). */
    val inDeck: StateFlow<Set<Int>> = (if (pickDeckId == 0L) flowOf(emptyList()) else decks.wordIds(pickDeckId))
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun selectTab(tab: LibraryTab) { _tab.value = tab }
    fun selectLevel(jlpt: Int) { _jlpt.value = jlpt }
    fun search(query: String) { _query.value = query }

    fun toggleInDeck(wordId: Int) {
        if (pickDeckId == 0L) return
        viewModelScope.launch {
            if (wordId in inDeck.value) decks.removeWord(pickDeckId, wordId) else decks.addWord(pickDeckId, wordId)
        }
    }
}
