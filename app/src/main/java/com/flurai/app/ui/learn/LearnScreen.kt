package com.flurai.app.ui.learn

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flurai.app.ui.components.PlannedFeature
import com.flurai.app.ui.components.TabScreen
import com.flurai.app.ui.study.VocabHubViewModel
import com.flurai.app.ui.theme.japanese

private val learnFeatures = listOf(
    PlannedFeature("会話", "Conversation partner", "Practice speaking and chatting with an AI tutor at your level.", 12),
)

@Composable
fun LearnScreen(
    onOpenFlashcards: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenTests: () -> Unit,
    onOpenGrammar: () -> Unit,
    onOpenReading: () -> Unit,
    viewModel: VocabHubViewModel = hiltViewModel(),
) {
    val hub by viewModel.state.collectAsStateWithLifecycle()
    TabScreen(
        titleJapanese = "学ぶ",
        title = "Learn",
        subtitle = "Words, grammar, tests and conversation",
        features = learnFeatures,
        header = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                EntryCard(
                    japanese = "単語",
                    title = "Flashcards",
                    body = when {
                        hub.due + hub.newLeftToday == 0 -> "All done for today. Come back tomorrow!"
                        else -> "${hub.due} to review · ${hub.newLeftToday} new words today"
                    },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    accent = MaterialTheme.colorScheme.secondaryContainer,
                    onClick = onOpenFlashcards,
                )
                EntryCard(
                    japanese = "辞書",
                    title = "Word library",
                    body = "Browse and search 8,000 words and 2,200 kanji from N5 to N1, with example sentences.",
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                    accent = MaterialTheme.colorScheme.primary,
                    onClick = onOpenLibrary,
                )
                EntryCard(
                    japanese = "文法",
                    title = "Grammar",
                    body = "N5 and N4 grammar points with explanations, examples and practice.",
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                    accent = MaterialTheme.colorScheme.secondary,
                    onClick = onOpenGrammar,
                )
                EntryCard(
                    japanese = "読解",
                    title = "Reading",
                    body = "Read real sentences at your level, tap any word to look it up, then check your understanding.",
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    ),
                    accent = MaterialTheme.colorScheme.tertiary,
                    onClick = onOpenReading,
                )
                EntryCard(
                    japanese = "試験",
                    title = "Tests",
                    body = "Placement test, level checkpoints and quick quizzes.",
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    accent = MaterialTheme.colorScheme.primary,
                    onClick = onOpenTests,
                )
            }
        },
    )
}

@Composable
private fun EntryCard(
    japanese: String,
    title: String,
    body: String,
    colors: CardColors,
    accent: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = colors) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(japanese, style = MaterialTheme.typography.headlineSmall.japanese(), color = accent)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}
