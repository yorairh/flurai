package com.flurai.app.ui.settings

import com.flurai.app.core.auth.uid
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flurai.app.core.activity.AppSettings
import com.flurai.app.core.activity.SettingsRepository
import com.flurai.app.core.auth.AuthRepository
import com.flurai.app.core.reminder.ReminderScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val repository: SettingsRepository,
    private val sync: com.flurai.app.core.sync.SyncEngine,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val isGuest: Boolean get() = auth.currentUser.value?.isGuest == true

    val syncStatus: StateFlow<com.flurai.app.core.sync.SyncStatus> = sync.status

    fun syncNow() {
        val uid = auth.uid ?: return
        viewModelScope.launch { sync.sync(uid) }
    }

    val settings: StateFlow<AppSettings> = auth.currentUser.flatMapLatest { user ->
        if (user == null) emptyFlow() else repository.settings(user.uid)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun setReminderEnabled(enabled: Boolean) = change { it.copy(reminderEnabled = enabled) }

    fun setReminderTime(hour: Int, minute: Int) = change { it.copy(reminderHour = hour, reminderMinute = minute) }

    fun setStreakMinutes(minutes: Int) = change { it.copy(streakMinutes = minutes) }

    private fun change(transform: (AppSettings) -> AppSettings) {
        val uid = auth.uid ?: return
        viewModelScope.launch {
            var saved = AppSettings()
            repository.update(uid) { current -> transform(current).also { saved = it } }
            ReminderScheduler.apply(context, saved.reminderEnabled, saved.reminderHour, saved.reminderMinute)
        }
    }
}
