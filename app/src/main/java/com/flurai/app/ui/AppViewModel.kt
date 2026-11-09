package com.flurai.app.ui

import kotlinx.coroutines.flow.first
import com.flurai.app.core.auth.uid
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.flurai.app.core.auth.AuthRepository
import com.flurai.app.core.auth.AuthUser
import com.flurai.app.core.profile.StudyProfile
import com.flurai.app.core.profile.StudyProfileRepository
import com.flurai.app.core.sync.SyncEngine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    private val sync: SyncEngine,
) : ViewModel() {

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<AppState> = auth.currentUser
        .flatMapLatest { user ->
            if (user == null) flowOf(AppState.SignedOut)
            else if (!user.emailVerified) flowOf(AppState.NeedsVerification(user))
            else flow {
                emit(AppState.Loading)
                if (profiles.profile(user.uid).first() == null) {
                    // A phone with no profile may belong to someone who has progress in the cloud: look
                    // there first (briefly) so they are not sent through onboarding again.
                    withTimeoutOrNull(RESTORE_TIMEOUT_MS) { sync.sync(user.uid) }
                } else {
                    // Everyone else gets the app straight away; the sync catches up in the background.
                    viewModelScope.launch { sync.sync(user.uid) }
                }
                emitAll(profiles.profile(user.uid).map { appStateOf(user, it) })
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppState.Loading)

    fun signOut() {
        val uid = auth.uid
        viewModelScope.launch {
            // Save progress to the cloud first (best effort) so the next sign-in finds it.
            if (uid != null) withTimeoutOrNull(SIGN_OUT_SYNC_TIMEOUT_MS) { sync.sync(uid) }
            auth.signOut()
        }
    }

    private companion object {
        const val RESTORE_TIMEOUT_MS = 12_000L
        const val SIGN_OUT_SYNC_TIMEOUT_MS = 6_000L
    }
}
