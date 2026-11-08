package com.flurai.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flurai.app.core.content.ContentDao
import com.flurai.app.core.content.KanjiEntity
import com.flurai.app.core.content.WordEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

enum class LibraryTab { Words, Kanji }

/** JLPT numbers in study order: N5 (5) first. */
val JlptNumbers = listOf(5, 4, 3, 2, 1)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val content: ContentDao,
) : ViewModel() {

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

    /** Null while loading. A search covers every level; otherwise the selected level is listed. */
    val words: StateFlow<List<WordEntity>?> = combine(_jlpt, _query.debounce(200)) { jlpt, query -> jlpt to query.trim() }
        .flatMapLatest { (jlpt, query) ->
            if (query.isEmpty()) content.wordsAtLevel(jlpt) else content.searchWords(query)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val kanji: StateFlow<List<KanjiEntity>?> = _jlpt
        .flatMapLatest { content.kanjiAtLevel(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun selectTab(tab: LibraryTab) { _tab.value = tab }
    fun selectLevel(jlpt: Int) { _jlpt.value = jlpt }
    fun search(query: String) { _query.value = query }
}
