package com.flurai.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flurai.app.core.auth.AuthRepository
import com.flurai.app.core.auth.AuthUser
import com.flurai.app.core.profile.StudyProfile
import com.flurai.app.core.profile.StudyProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Which part of the app to show. */
sealed interface AppState {
    data object Loading : AppState
    data object SignedOut : AppState
    data class NeedsVerification(val user: AuthUser) : AppState
    data class NeedsOnboarding(val user: AuthUser) : AppState
    data class Ready(val user: AuthUser, val profile: StudyProfile) : AppState
}

fun appStateOf(user: AuthUser?, profile: StudyProfile?): AppState = when {
    user == null -> AppState.SignedOut
    !user.emailVerified -> AppState.NeedsVerification(user)
    profile == null -> AppState.NeedsOnboarding(user)
    else -> AppState.Ready(user, profile)
}

@HiltViewModel
class AppViewModel @Inject constructor(
    private val auth: AuthRepository,
    profiles: StudyProfileRepository,
) : ViewModel() {

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<AppState> = auth.currentUser
        .flatMapLatest { user ->
            if (user == null) flowOf(AppState.SignedOut)
            else if (!user.emailVerified) flowOf(AppState.NeedsVerification(user))
            else profiles.profile(user.uid).map { appStateOf(user, it) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppState.Loading)

    fun signOut() = auth.signOut()
}
