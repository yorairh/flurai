package com.flurai.app.ui.explore

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

private val exploreFeatures = listOf(
    PlannedFeature("作品", "Anime, shows & manga", "Media matched to your level, with how many of its words you already know.", 10),
)

@Composable
fun ExploreScreen(onOpenVideos: () -> Unit) {
    TabScreen(
        titleJapanese = "探す",
        title = "Explore",
        subtitle = "Read, watch and immerse at your level",
        features = exploreFeatures,
        header = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Card(
                    onClick = onOpenVideos,
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("動画", style = MaterialTheme.typography.headlineSmall.japanese(), color = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Videos", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "YouTube channels for your level, and videos on the grammar you miss.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    }
                }
            }
        },
    )
}
