package com.flurai.app.ui.study

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flurai.app.core.auth.AuthRepository
import com.flurai.app.core.profile.StudyProfileRepository
import com.flurai.app.core.study.ItemType
import com.flurai.app.core.study.KanaData
import com.flurai.app.core.study.KanaScript
import com.flurai.app.core.study.StudyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import javax.inject.Inject

data class DeckProgress(val studying: Int, val total: Int)

data class VocabHubState(
    val due: Int = 0,
    val newLeftToday: Int = 0,
    /** JLPT number → progress. */
    val levels: Map<Int, DeckProgress> = emptyMap(),
    val kana: Map<KanaScript, DeckProgress> = emptyMap(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class VocabHubViewModel @Inject constructor(
    auth: AuthRepository,
    profiles: StudyProfileRepository,
    repository: StudyRepository,
) : ViewModel() {

    private val wordLevels = flow { emit(repository.wordLevels()) }

    val state: StateFlow<VocabHubState> = auth.currentUser.flatMapLatest { user ->
        if (user == null) return@flatMapLatest emptyFlow()
        val now = Instant.now()
        val newLeft = combine(profiles.profile(user.uid), repository.introducedToday(user.uid, now)) { profile, introduced ->
            ((profile?.newCardsPerDay ?: 0) - introduced).coerceAtLeast(0)
        }
        combine(
            repository.dueCount(user.uid, now),
            newLeft,
            repository.introducedItems(user.uid),
            wordLevels,
        ) { due, left, introduced, levels ->
            val studiedWords = introduced.filter { it.itemType == ItemType.Word }.mapNotNull { levels[it.itemId] }
            val studiedKana = introduced.filter { it.itemType == ItemType.Kana }.mapNotNull { KanaData.byId(it.itemId)?.script }
            VocabHubState(
                due = due,
                newLeftToday = left,
                levels = levels.values.groupingBy { it }.eachCount().mapValues { (jlpt, total) ->
                    DeckProgress(studiedWords.count { it == jlpt }, total)
                },
                kana = KanaScript.entries.associateWith { script ->
                    DeckProgress(studiedKana.count { it == script }, KanaData.of(script).size)
                },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VocabHubState())
}
