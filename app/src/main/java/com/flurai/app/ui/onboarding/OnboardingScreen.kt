package com.flurai.app.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flurai.app.core.auth.AuthUser
import com.flurai.app.core.model.JlptLevel
import com.flurai.app.core.profile.StudyProfile
import com.flurai.app.ui.theme.japanese

@Composable
fun OnboardingScreen(
    user: AuthUser,
    onSignOut: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler(enabled = state.stepIndex > 0) { viewModel.back() }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator(
                progress = { (state.stepIndex + 1f) / state.steps.size },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onSignOut) { Text("Sign out") }
        }
        if (state.stepIndex == 0) com.flurai.app.ui.account.MoveProgressCard(Modifier.padding(top = 8.dp))

        AnimatedContent(
            targetState = state.step,
            label = "onboarding-step",
            modifier = Modifier.weight(1f),
        ) { step ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (step) {
                    OnboardingStep.Level -> LevelStep(user.name, state.level, viewModel::selectLevel)
                    OnboardingStep.Kana -> KanaStep(state, viewModel)
                    OnboardingStep.Goal -> GoalStep(state, viewModel)
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.stepIndex > 0) {
                TextButton(onClick = { viewModel.back() }) { Text("Back") }
            }
            Spacer(Modifier.weight(1f))
            val last = state.stepIndex == state.steps.lastIndex
            Button(
                onClick = { viewModel.next(user.uid) },
                enabled = state.canContinue && !state.saving,
                modifier = Modifier.height(52.dp),
            ) {
                if (state.saving) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else Text(if (last) "Start learning" else "Continue")
            }
        }
    }
}

@Composable
private fun StepHeader(japanese: String, title: String, subtitle: String) {
    Text(japanese, style = MaterialTheme.typography.titleMedium.japanese(), color = MaterialTheme.colorScheme.secondary)
    Text(title, style = MaterialTheme.typography.headlineMedium)
    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun LevelStep(name: String, selected: JlptLevel?, onSelect: (JlptLevel) -> Unit) {
    val greeting = if (name.isBlank()) "Nice to meet you!" else "Nice to meet you, $name!"
    StepHeader(
        japanese = "レベル",
        title = greeting,
        subtitle = "Where are you starting from? Pick the level that fits you now; " +
            "the app will adjust as it learns what you know.",
    )
    JlptLevel.entries.forEach { level ->
        LevelCard(level, selected == level) { onSelect(level) }
    }
}

@Composable
internal fun LevelCard(level: JlptLevel, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) colors.primaryContainer else colors.surfaceContainerLow,
        ),
        border = if (selected) BorderStroke(2.dp, colors.primary) else null,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                level.label,
                style = MaterialTheme.typography.titleLarge,
                color = colors.primary,
                modifier = Modifier.width(104.dp),
            )
            Text(level.description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun KanaStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    StepHeader(
        japanese = "かな",
        title = "Can you read kana?",
        subtitle = "Japanese uses two syllable alphabets. We'll start you with the ones you don't know yet.",
    )
    KanaToggle("ひらがな", "Hiragana", "あ い う え お", state.knowsHiragana, viewModel::setKnowsHiragana)
    KanaToggle("カタカナ", "Katakana", "ア イ ウ エ オ", state.knowsKatakana, viewModel::setKnowsKatakana)
}

@Composable
internal fun KanaToggle(
    japanese: String,
    name: String,
    sample: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("$japanese · $name", style = MaterialTheme.typography.titleMedium.japanese())
                Text(sample, style = MaterialTheme.typography.headlineSmall.japanese(), color = MaterialTheme.colorScheme.primary)
                Text("I can read these", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun GoalStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    StepHeader(
        japanese = "目標",
        title = "Set a daily goal",
        subtitle = "Small and steady beats big and rare. You can change this any time on the Me tab.",
    )
    GoalPickers(state, viewModel)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GoalPickers(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    Text("Study time per day", style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StudyProfile.DailyMinuteOptions.forEach { minutes ->
            FilterChip(
                selected = state.dailyMinutes == minutes,
                onClick = { viewModel.setDailyMinutes(minutes) },
                label = { Text("$minutes min") },
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Text("New words per day", style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StudyProfile.NewCardOptions.forEach { count ->
            FilterChip(
                selected = state.newCardsPerDay == count,
                onClick = { viewModel.setNewCardsPerDay(count) },
                label = { Text("$count") },
            )
        }
    }
    Text(
        "Each new word comes back for review over the following days, so 10 new words a day " +
            "means roughly 100 reviews a day after a few weeks.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
