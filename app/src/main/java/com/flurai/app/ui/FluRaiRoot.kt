package com.flurai.app.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flurai.app.ui.auth.AuthFlow
import com.flurai.app.ui.onboarding.OnboardingScreen

/** Routes between sign-in, onboarding and the main app based on [AppState]. */
@Composable
fun FluRaiRoot(viewModel: AppViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Surface(Modifier.fillMaxSize()) {
        // Crossfade by screen kind, so the main app isn't rebuilt when the user or profile changes.
        Crossfade(targetState = state::class, label = "app-state") { kind ->
            when (kind) {
                AppState.Loading::class -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                AppState.SignedOut::class -> AuthFlow()
                AppState.NeedsOnboarding::class -> (state as? AppState.NeedsOnboarding)?.let {
                    OnboardingScreen(user = it.user, onSignOut = viewModel::signOut)
                }
                else -> (state as? AppState.Ready)?.let {
                    FluRaiAppShell(user = it.user, profile = it.profile, onSignOut = viewModel::signOut)
                }
            }
        }
    }
}
