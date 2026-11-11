package com.flurai.app.ui.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flurai.app.core.auth.AuthRepository
import com.flurai.app.core.knowledge.ProgressRepository
import com.flurai.app.core.knowledge.ProgressSnapshot
import com.flurai.app.core.profile.StudyProfileRepository
import com.flurai.app.core.study.StudyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class ProgressUiState(
    /** Null while the first numbers are being computed. */
    val snapshot: ProgressSnapshot? = null,
    val dueNow: Int = 0,
    val newLeftToday: Int = 0,
)

/** Feeds both the Home summary and the full Progress screen. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProgressViewModel @Inject constructor(
    auth: AuthRepository,
    profiles: StudyProfileRepository,
    progress: ProgressRepository,
    study: StudyRepository,
) : ViewModel() {

    val state: StateFlow<ProgressUiState> = auth.currentUser.flatMapLatest { user ->
        if (user == null) return@flatMapLatest emptyFlow()
        profiles.profile(user.uid).filterNotNull().flatMapLatest { profile ->
            combine(
                progress.snapshot(user.uid),
                study.dueCount(user.uid),
                study.introducedToday(user.uid),
                study.unstartedCount(user.uid),
            ) { snapshot, due, introduced, unstarted ->
                ProgressUiState(
                    snapshot = snapshot,
                    dueNow = due,
                    newLeftToday = (profile.newCardsPerDay - introduced).coerceAtLeast(0) + unstarted,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProgressUiState())
}
