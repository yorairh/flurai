package com.flurai.app.ui.home

import com.flurai.app.core.study.StudyDay
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flurai.app.core.activity.ActivitySummary
import com.flurai.app.core.activity.SettingsRepository
import com.flurai.app.core.activity.StudyActivityRepository
import com.flurai.app.core.activity.studyEpochDay
import com.flurai.app.core.activity.summarize
import com.flurai.app.core.auth.AuthRepository
import com.flurai.app.core.profile.StudyProfileRepository
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

/** Study time, streak and heat map for the Home card. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ActivityViewModel @Inject constructor(
    auth: AuthRepository,
    profiles: StudyProfileRepository,
    settings: SettingsRepository,
    activity: StudyActivityRepository,
) : ViewModel() {

    val summary: StateFlow<ActivitySummary?> = auth.currentUser.flatMapLatest { user ->
        if (user == null) return@flatMapLatest emptyFlow()
        // A new study day changes the streak even when nothing else does.
        StudyDay.ticks().flatMapLatest { now ->
            combine(
                activity.recent(user.uid, now),
                settings.settings(user.uid),
                profiles.profile(user.uid).filterNotNull(),
            ) { days, appSettings, profile ->
                summarize(days, studyEpochDay(now), profile.dailyMinutes, appSettings.streakMinutes)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
