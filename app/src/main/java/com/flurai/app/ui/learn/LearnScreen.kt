package com.flurai.app.ui.learn

import androidx.compose.runtime.Composable
import com.flurai.app.ui.components.PlannedFeature
import com.flurai.app.ui.components.TabScreen

private val learnFeatures = listOf(
    PlannedFeature("単語", "Vocabulary cards", "Anki-style spaced repetition for N5–N1 words, with audio and example sentences.", 3),
    PlannedFeature("文法", "Grammar", "Grammar points by JLPT level: explanations, examples and exercises.", 7),
    PlannedFeature("試験", "Tests", "Placement test, JLPT-style level checks, and review quizzes.", 6),
    PlannedFeature("会話", "Conversation partner", "Practice speaking and chatting with an AI tutor at your level.", 12),
)

@Composable
fun LearnScreen() {
    TabScreen(
        titleJapanese = "学ぶ",
        title = "Learn",
        subtitle = "Words, grammar, tests and conversation",
        features = learnFeatures,
    )
}
