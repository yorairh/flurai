package com.flurai.app.ui.profile

import androidx.compose.runtime.Composable
import com.flurai.app.ui.components.PlannedFeature
import com.flurai.app.ui.components.TabScreen

private val profileFeatures = listOf(
    PlannedFeature("登録", "Account", "Sign in with your name and email; your progress syncs across devices.", 1),
    PlannedFeature("進歩", "Progress", "What the app thinks you know, and where to improve.", 4),
    PlannedFeature("設定", "Settings", "Daily goal, practice reminders, and theme.", 5),
)

@Composable
fun ProfileScreen() {
    TabScreen(
        titleJapanese = "マイページ",
        title = "Me",
        subtitle = "Account, progress and settings",
        features = profileFeatures,
    )
}
