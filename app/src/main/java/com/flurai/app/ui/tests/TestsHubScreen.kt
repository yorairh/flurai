package com.flurai.app.ui.tests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flurai.app.core.quiz.CHECKPOINT_PASS_FRACTION
import com.flurai.app.core.quiz.TestResultEntity
import com.flurai.app.core.quiz.TestType
import com.flurai.app.ui.components.SectionHeader
import com.flurai.app.ui.theme.japanese
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestsHubScreen(
    onBack: () -> Unit,
    onStart: (QuizKind) -> Unit,
    viewModel: TestsHubViewModel = hiltViewModel(),
) {
    val results by viewModel.results.collectAsStateWithLifecycle()
    val placement = results.firstOrNull { it.type == TestType.Placement }
    val quick = results.firstOrNull { it.type == TestType.Quick }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Tests") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            },
            windowInsets = WindowInsets(0),
        )
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TestCard(
                japanese = "配置",
                title = "Placement test",
                body = placement?.let { "Last result: ${placementLabel(it)}" }
                    ?: "About 15 to 25 questions that find your level. It adapts as you answer.",
                onClick = { onStart(QuizKind.Placement) },
            )
            TestCard(
                japanese = "復習",
                title = "Quick quiz",
                body = quick?.let { "Last score: ${it.correct}/${it.total}" } ?: "Ten questions mixing words you study with new ones.",
                onClick = { onStart(QuizKind.Quick) },
            )

            SectionHeader("級", "Level checkpoints", "20 questions on one level's words and kanji. Score 80% to pass.")
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column {
                    listOf(5, 4, 3, 2, 1).forEachIndexed { index, jlpt ->
                        if (index > 0) HorizontalDivider()
                        val best = results.filter { it.type == TestType.Checkpoint && it.level == jlpt }.maxByOrNull { it.fraction }
                        CheckpointRow(jlpt, best) { onStart(QuizKind.Checkpoint(jlpt)) }
                    }
                }
            }
            Text(
                "Grammar and reading checks arrive with those features.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun placementLabel(result: TestResultEntity): String =
    if (result.level == 0) "Beginner" else "N${result.level}"

@Composable
private fun TestCard(japanese: String, title: String, body: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(japanese, style = MaterialTheme.typography.headlineSmall.japanese(), color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@Composable
private fun CheckpointRow(jlpt: Int, best: TestResultEntity?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("N$jlpt", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        val passed = best != null && best.fraction >= CHECKPOINT_PASS_FRACTION
        Text(
            when {
                best == null -> "Not taken"
                passed -> "Passed · ${(best.fraction * 100).roundToInt()}%"
                else -> "Best ${(best.fraction * 100).roundToInt()}%"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (passed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}
