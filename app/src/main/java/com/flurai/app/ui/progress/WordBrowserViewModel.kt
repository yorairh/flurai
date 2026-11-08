package com.flurai.app.ui.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flurai.app.core.auth.AuthRepository
import com.flurai.app.core.content.WordEntity
import com.flurai.app.core.knowledge.Knowledge
import com.flurai.app.core.knowledge.ProgressRepository
import com.flurai.app.core.knowledge.knowledge
import com.flurai.app.core.study.CardEntity
import com.flurai.app.core.study.CardKind
import com.flurai.app.core.study.ItemType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

enum class BrowserFilter(val label: String) {
    All("All"),
    Learning("Learning"),
    Known("Known"),
    Mature("Mature"),
    Suspended("Suspended"),
}

data class BrowserRow(val word: WordEntity, val knowledge: Knowledge, val card: CardEntity)

/**
 * Suspended words only show under "Suspended" (and "All"). Words added to study but not answered yet
 * count as learning.
 */
fun BrowserRow.matches(filter: BrowserFilter): Boolean = when (filter) {
    BrowserFilter.All -> true
    BrowserFilter.Suspended -> card.suspended
    BrowserFilter.Learning -> !card.suspended && (knowledge == Knowledge.Learning || knowledge == Knowledge.Unseen)
    BrowserFilter.Known -> !card.suspended && knowledge == Knowledge.Known
    BrowserFilter.Mature -> !card.suspended && knowledge == Knowledge.Mature
}

data class BrowserState(
    val loading: Boolean = true,
    val rows: List<BrowserRow> = emptyList(),
    /** Words per filter, within the selected level. */
    val counts: Map<BrowserFilter, Int> = emptyMap(),
    val filter: BrowserFilter = BrowserFilter.All,
    /** JLPT number, or null for every level. */
    val level: Int? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class WordBrowserViewModel @Inject constructor(
    auth: AuthRepository,
    private val progress: ProgressRepository,
) : ViewModel() {

    private val filter = MutableStateFlow(BrowserFilter.All)
    private val level = MutableStateFlow<Int?>(null)

    val state: StateFlow<BrowserState> = auth.currentUser.flatMapLatest { user ->
        if (user == null) return@flatMapLatest emptyFlow()
        combine(progress.cards(user.uid), filter, level) { cards, selectedFilter, selectedLevel ->
            val words = progress.catalog().words
            val rows = cards
                .filter { it.itemType == ItemType.Word && it.kind == CardKind.Recognition }
                .mapNotNull { card -> words[card.itemId]?.let { BrowserRow(it, card.knowledge(), card) } }
                .filter { selectedLevel == null || it.word.jlpt == selectedLevel }
                .sortedWith(compareByDescending<BrowserRow> { it.word.jlpt }.thenBy { it.word.id })
            BrowserState(
                loading = false,
                rows = rows.filter { it.matches(selectedFilter) },
                counts = BrowserFilter.entries.associateWith { f -> rows.count { it.matches(f) } },
                filter = selectedFilter,
                level = selectedLevel,
            )
        }.flowOn(Dispatchers.Default)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowserState())

    fun selectFilter(value: BrowserFilter) { filter.value = value }
    fun selectLevel(jlpt: Int?) { level.value = jlpt }
}
