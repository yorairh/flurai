package com.flurai.app.ui.explore

import androidx.compose.runtime.Composable
import com.flurai.app.ui.components.PlannedFeature
import com.flurai.app.ui.components.TabScreen

private val exploreFeatures = listOf(
    PlannedFeature("動画", "Videos", "YouTube lessons picked for your level and the grammar you're working on.", 9),
    PlannedFeature("作品", "Anime, shows & manga", "Media matched to your level, with how many of its words you already know.", 10),
)

@Composable
fun ExploreScreen() {
    TabScreen(
        titleJapanese = "探す",
        title = "Explore",
        subtitle = "Read, watch and immerse at your level",
        features = exploreFeatures,
    )
}
