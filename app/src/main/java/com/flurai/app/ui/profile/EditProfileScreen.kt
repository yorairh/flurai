package com.flurai.app.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flurai.app.core.auth.AuthUser
import com.flurai.app.core.model.JlptLevel
import com.flurai.app.core.profile.StudyProfile
import com.flurai.app.ui.onboarding.GoalPickers
import com.flurai.app.ui.onboarding.KanaToggle
import com.flurai.app.ui.onboarding.LevelCard
import com.flurai.app.ui.onboarding.OnboardingViewModel
import com.flurai.app.ui.theme.japanese

/** Change the choices made at onboarding: level, kana knowledge and daily goal. */
@Composable
fun EditProfileScreen(
    user: AuthUser,
    profile: StudyProfile,
    onDone: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.startEditing(profile) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved) { if (state.saved) onDone() }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("設定", style = MaterialTheme.typography.labelLarge.japanese(), color = MaterialTheme.colorScheme.secondary)
            Text("Level & goals", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Changes apply right away. This chooses which word list new words come from; your level is worked out from what you know and your checkpoints.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(4.dp))
            Text("Study new words from", style = MaterialTheme.typography.titleMedium)
            JlptLevel.entries.forEach { level ->
                LevelCard(level, state.level == level) { viewModel.selectLevel(level) }
            }

            if (state.level == JlptLevel.Beginner) {
                Spacer(Modifier.height(4.dp))
                Text("Kana", style = MaterialTheme.typography.titleMedium)
                KanaToggle("ひらがな", "Hiragana", "あ い う え お", state.knowsHiragana, viewModel::setKnowsHiragana)
                KanaToggle("カタカナ", "Katakana", "ア イ ウ エ オ", state.knowsKatakana, viewModel::setKnowsKatakana)
            }

            Spacer(Modifier.height(4.dp))
            GoalPickers(state, viewModel)
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            TextButton(onClick = onDone) { Text("Cancel") }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { viewModel.saveEdits(user.uid) },
                enabled = state.level != null && !state.saving,
                modifier = Modifier.height(52.dp),
            ) {
                if (state.saving) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else Text("Save")
            }
        }
    }
}
