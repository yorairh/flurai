package com.flurai.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.flurai.app.core.auth.AuthUser
import com.flurai.app.core.model.JlptLevel
import com.flurai.app.core.profile.StudyProfile
import com.flurai.app.ui.components.PlannedFeature
import com.flurai.app.ui.components.TabScreen
import com.flurai.app.ui.theme.FluRaiTheme
import com.flurai.app.ui.theme.japanese

private val homeFeatures = listOf(
    PlannedFeature("今日", "Today's plan", "Reviews due, new cards, and the one thing to improve right now.", 4),
    PlannedFeature("連続", "Streak & study time", "Minutes studied each day, your streak, and practice reminders.", 5),
    PlannedFeature("級", "Your level", "Estimated JLPT level, with coverage of N5–N1 vocabulary, kanji and grammar.", 4),
)

@Composable
fun HomeScreen(user: AuthUser, profile: StudyProfile) {
    TabScreen(
        titleJapanese = "ホーム",
        title = "Home",
        subtitle = "Your daily study hub",
        features = homeFeatures,
        header = { WelcomeCard(user, profile) },
    )
}

@Composable
private fun WelcomeCard(user: AuthUser, profile: StudyProfile) {
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
                Text("おかえり", style = MaterialTheme.typography.titleMedium.japanese())
                Text(
                    if (user.name.isBlank()) "Welcome back" else "Welcome back, ${user.name}",
                    style = MaterialTheme.typography.titleLarge,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(profile.level.label)
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
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    FluRaiTheme {
        HomeScreen(
            user = AuthUser("uid", "Aki", "aki@example.com"),
            profile = StudyProfile(JlptLevel.N5, 15, 10, knowsHiragana = true, knowsKatakana = true),
        )
    }
}
