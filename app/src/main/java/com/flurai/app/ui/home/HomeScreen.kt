package com.flurai.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flurai.app.core.activity.ActivitySummary
import com.flurai.app.core.auth.AuthUser
import com.flurai.app.core.knowledge.ProgressSnapshot
import com.flurai.app.core.model.JlptLevel
import com.flurai.app.core.profile.StudyProfile
import com.flurai.app.ui.components.PlannedFeature
import com.flurai.app.ui.components.TabScreen
import com.flurai.app.ui.progress.ProgressUiState
import com.flurai.app.ui.progress.ProgressViewModel
import com.flurai.app.ui.progress.forgottenLabel
import com.flurai.app.ui.theme.FluRaiTheme
import com.flurai.app.ui.theme.japanese
import kotlin.math.roundToInt

private val homeFeatures = emptyList<PlannedFeature>()

@Composable
fun HomeScreen(
    user: AuthUser,
    profile: StudyProfile,
    onStudy: () -> Unit,
    onOpenProgress: () -> Unit,
    viewModel: ProgressViewModel = hiltViewModel(),
    activityViewModel: ActivityViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activity by activityViewModel.summary.collectAsStateWithLifecycle()
    HomeContent(user, profile, state, activity, onStudy, onOpenProgress)
}

@Composable
private fun HomeContent(
    user: AuthUser,
    profile: StudyProfile,
    state: ProgressUiState,
    activity: ActivitySummary?,
    onStudy: () -> Unit,
    onOpenProgress: () -> Unit,
) {
    TabScreen(
        titleJapanese = "ホーム",
        title = "Home",
        subtitle = "Your daily study hub",
        features = homeFeatures,
        header = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Someone who has never studied is greeted, not welcomed back.
                val returning = (state.snapshot?.studiedWords ?: 0) > 0 || activity?.heat?.any { it > 0 } == true
                WelcomeCard(user, profile, returning)
                state.snapshot?.let { snapshot ->
                    TodayCard(state, snapshot, onStudy)
                    activity?.let { StreakCard(it) }
                    LevelCard(snapshot, onOpenProgress)
                }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WelcomeCard(user: AuthUser, profile: StudyProfile, returning: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "流",
                style = MaterialTheme.typography.displayLarge.japanese().copy(fontSize = 64.sp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            )
            Spacer(Modifier.width(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (returning) "おかえり" else "ようこそ", style = MaterialTheme.typography.titleMedium.japanese())
                val greeting = if (returning) "Welcome back" else "Welcome"
                Text(
                    if (user.name.isBlank()) greeting else "$greeting, ${user.name}",
                    style = MaterialTheme.typography.titleLarge,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Pill("Studying ${profile.level.label}")
                    Pill("${profile.dailyMinutes} min/day")
                    Pill("${profile.newCardsPerDay} new/day")
                }
            }
        }
    }
}

@Composable
private fun Pill(text: String) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.primaryContainer) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            softWrap = false,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** Today's plan: what's due, the new-word allowance, and the word to focus on. */
@Composable
private fun TodayCard(state: ProgressUiState, snapshot: ProgressSnapshot, onStudy: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("今日", style = MaterialTheme.typography.titleMedium.japanese(), color = MaterialTheme.colorScheme.secondary)
            Row {
                Stat(state.dueNow, "to review", Modifier.weight(1f))
                Stat(state.newLeftToday, "new words", Modifier.weight(1f))
            }
            snapshot.weak.firstOrNull()?.let { weak ->
                Text(
                    "Focus: ${weak.word.expression} (${forgottenLabel(weak.lapses).lowercase()})",
                    style = MaterialTheme.typography.bodyMedium.japanese(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.dueNow + state.newLeftToday == 0) {
                Text("All done for today. Come back tomorrow!", style = MaterialTheme.typography.titleMedium)
            } else {
                Button(onClick = onStudy, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Study now") }
            }
        }
    }
}

@Composable
private fun Stat(value: Int, label: String, modifier: Modifier) {
    Column(modifier) {
        Text("$value", style = MaterialTheme.typography.displaySmall)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The estimated level and progress to the next one; opens the full progress screen. */
@Composable
private fun LevelCard(snapshot: ProgressSnapshot, onClick: () -> Unit) {
    val estimate = snapshot.estimate
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("級", style = MaterialTheme.typography.titleMedium.japanese(), color = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(8.dp))
                Text("Your level", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
            }
            Text(estimate.current.label, style = MaterialTheme.typography.headlineMedium)
            estimate.goal?.let { goal ->
                LinearProgressIndicator(progress = { estimate.goalProgress }, modifier = Modifier.fillMaxWidth())
                Text(
                    "${goal.label}: ${(estimate.goalWords * 100).roundToInt()}% of words, ${(estimate.goalGrammar * 100).roundToInt()}% of grammar known",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                "${snapshot.knownWords} words known · see progress",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    FluRaiTheme {
        HomeContent(
            user = AuthUser("uid", "Aki", "aki@example.com"),
            profile = StudyProfile(JlptLevel.N5, 15, 10, knowsHiragana = true, knowsKatakana = true),
            state = ProgressUiState(),
            activity = null,
            onStudy = {},
            onOpenProgress = {},
        )
    }
}
