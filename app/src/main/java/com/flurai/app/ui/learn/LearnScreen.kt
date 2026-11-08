package com.flurai.app.ui.learn

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.flurai.app.ui.components.PlannedFeature
import com.flurai.app.ui.components.TabScreen
import com.flurai.app.ui.theme.japanese

private val learnFeatures = listOf(
    PlannedFeature("単語", "Vocabulary cards", "Anki-style spaced repetition for N5–N1 words, with audio and example sentences.", 3),
    PlannedFeature("文法", "Grammar", "Grammar points by JLPT level: explanations, examples and exercises.", 7),
    PlannedFeature("試験", "Tests", "Placement test, JLPT-style level checks, and review quizzes.", 6),
    PlannedFeature("会話", "Conversation partner", "Practice speaking and chatting with an AI tutor at your level.", 12),
)

@Composable
fun LearnScreen(onOpenLibrary: () -> Unit) {
    TabScreen(
        titleJapanese = "学ぶ",
        title = "Learn",
        subtitle = "Words, grammar, tests and conversation",
        features = learnFeatures,
        header = { LibraryCard(onOpenLibrary) },
    )
}

@Composable
private fun LibraryCard(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("辞書", style = MaterialTheme.typography.headlineSmall.japanese(), color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Word library", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Browse and search 8,000 words and 2,200 kanji from N5 to N1, with example sentences.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}
