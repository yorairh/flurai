package com.flurai.app.ui.grammar

import com.flurai.app.ui.components.LevelChips
import com.flurai.app.ui.components.BackTopAppBar
import com.flurai.app.ui.components.MissingVoiceBanner
import com.flurai.app.ui.components.SentenceSpeakButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import com.flurai.app.ui.components.rememberStudyScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flurai.app.core.content.GrammarPointEntity
import com.flurai.app.core.grammar.GrammarStatus
import com.flurai.app.ui.components.SectionHeader
import com.flurai.app.ui.theme.japanese

/** All grammar points grouped by level, with how well each is known. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrammarListScreen(
    onBack: () -> Unit,
    onOpenPoint: (Int) -> Unit,
    onPractice: (jlpt: Int) -> Unit,
    viewModel: GrammarListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        BackTopAppBar(title = { Text("Grammar") }, onBack = onBack)
        // One level at a time, like the Reading screen: with hundreds of points a single list is too long.
        LevelChips(selected, viewModel::select, Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        // Nothing until the points are loaded: a list that starts with only its footer stays anchored
        // to it when the rows arrive, and would open scrolled to the bottom.
        if (state.levels.isEmpty()) return@Column
        // Lazy, so only the rows on screen are built.
        // A new list state per level, so each level starts at its top.
        androidx.compose.runtime.key(selected) {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        ) {
            state.levels.filter { it.jlpt == selected }.forEach { level ->
                item(key = "header-${level.jlpt}") {
                    Box(Modifier.padding(vertical = 6.dp)) {
                        SectionHeader(
                            "N${level.jlpt}",
                            "N${level.jlpt} grammar",
                            "${level.known} of ${level.points.size} known · ${level.learning} in progress",
                        )
                    }
                }
                item(key = "practice-${level.jlpt}") {
                    OutlinedButton(onClick = { onPractice(level.jlpt) }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text("Practice N${level.jlpt} grammar")
                    }
                }
                // The rows of one level look like a single card: round only the first and last.
                itemsIndexed(level.points, key = { _, item -> item.point.id }) { index, item ->
                    val last = index == level.points.lastIndex
                    val top = if (index == 0) 12.dp else 0.dp
                    val bottom = if (last) 12.dp else 0.dp
                    Box(Modifier.padding(bottom = bottom)) {
                        Surface(
                            shape = RoundedCornerShape(top, top, bottom, bottom),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Column {
                                if (index > 0) HorizontalDivider()
                                PointRow(item.point, item.status) { onOpenPoint(item.point.id) }
                            }
                        }
                    }
                }
            }
            item(key = "footer") {
                Text(
                    "Grammar for every level from N5 to N1. Examples are written for this app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        }
    }
}

@Composable
private fun PointRow(point: GrammarPointEntity, status: GrammarStatus, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(point.pattern, style = MaterialTheme.typography.titleMedium.japanese())
            Text(point.meaning, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            when (status) {
                GrammarStatus.New -> "New"
                GrammarStatus.Learning -> "Learning"
                GrammarStatus.Known -> "Known"
            },
            style = MaterialTheme.typography.labelLarge,
            color = if (status == GrammarStatus.Known) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrammarDetailScreen(
    onBack: () -> Unit,
    onPractice: (jlpt: Int, pointId: Int) -> Unit,
    viewModel: GrammarDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scroll = rememberStudyScroll(viewModel.clock)
    val point = state.point
    Column(Modifier.fillMaxSize()) {
        BackTopAppBar(title = { Text(point?.let { "N${it.jlpt} grammar" } ?: "Grammar") }, onBack = onBack)
        MissingVoiceBanner(viewModel.speaker)
        if (point == null) return@Column
        Column(
            Modifier.verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(point.pattern, style = MaterialTheme.typography.displaySmall.japanese())
            Text(point.title, style = MaterialTheme.typography.titleMedium)
            Text(point.meaning, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text("How to build it", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(point.formation, style = MaterialTheme.typography.bodyLarge.japanese())
                }
            }
            Text(point.explanation, style = MaterialTheme.typography.bodyLarge.japanese())

            Text("Examples", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            state.examples.forEach { example ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(example.japanese, style = MaterialTheme.typography.titleLarge.japanese())
                            Spacer(Modifier.width(4.dp))
                            Text(example.english, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(8.dp))
                        SentenceSpeakButton(onSpeak = { slow -> viewModel.speak(example.japanese, slow) })
                    }
                }
            }

            state.progressText?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(
                onClick = { onPractice(point.jlpt, point.id) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Practice this point") }
            val context = androidx.compose.ui.platform.LocalContext.current
            OutlinedButton(
                onClick = {
                    val url = com.flurai.app.core.video.searchUrl(com.flurai.app.core.video.grammarQuery(point.pattern))
                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Find videos about this") }
            Spacer(Modifier.height(16.dp))
        }
    }
}
