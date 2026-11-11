package com.flurai.app.ui.progress

import com.flurai.app.ui.components.LoadingBox
import com.flurai.app.ui.components.BackTopAppBar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flurai.app.core.knowledge.KanjiProgress
import com.flurai.app.core.knowledge.KnowledgeRules
import com.flurai.app.core.knowledge.LevelProgress
import com.flurai.app.core.knowledge.ProgressSnapshot
import com.flurai.app.core.knowledge.WeakWord
import com.flurai.app.ui.components.SectionHeader
import com.flurai.app.ui.theme.japanese
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen(
    onBack: () -> Unit,
    onOpenWord: (Int) -> Unit,
    onBrowseWords: () -> Unit,
    onStudy: () -> Unit,
    viewModel: ProgressViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        BackTopAppBar(title = { Text("Progress") }, onBack = onBack)
        val snapshot = state.snapshot
        if (snapshot == null) {
            LoadingBox()
            return@Column
        }
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { LevelCard(snapshot) }

            item { SectionHeader("語彙", "Vocabulary", "How many words of each level you know.") }
            item { Legend() }
            items(snapshot.vocab, key = { it.jlpt }) { VocabRow(it) }

            item { SectionHeader("漢字", "Kanji", "Kanji that appear in words you know.") }
            item { KanjiCard(snapshot.kanji) }

            item { SectionHeader("文法", "Grammar", "Grammar points you answered right three times, with the latest answer right.") }
            item { GrammarCard(snapshot.vocab) }

            item { SectionHeader("弱点", "Improve now", "Words you've forgotten before, worst first.") }
            if (snapshot.weak.isEmpty()) {
                item {
                    InfoCard("No trouble spots yet. Words you forget while reviewing will show up here.")
                }
            } else {
                items(snapshot.weak, key = { it.word.id }) { WeakWordRow(it) { onOpenWord(it.word.id) } }
            }
            if (state.dueNow > 0) {
                item {
                    Button(onClick = onStudy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Text("Review ${state.dueNow} due ${if (state.dueNow == 1) "card" else "cards"}")
                    }
                }
            }

            item { SectionHeader("予定", "Coming up", "Cards due over the next week.") }
            item { Forecast(snapshot.forecast) }

            item {
                OutlinedButton(onClick = onBrowseWords, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("Browse your words")
                }
            }
        }
    }
}

@Composable
private fun LevelCard(snapshot: ProgressSnapshot) {
    val estimate = snapshot.estimate
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("級", style = MaterialTheme.typography.titleMedium.japanese(), color = MaterialTheme.colorScheme.secondary)
            Text("Your level", style = MaterialTheme.typography.labelLarge)
            Text(estimate.current.label, style = MaterialTheme.typography.displaySmall)
            Text(estimate.current.description, style = MaterialTheme.typography.bodyMedium)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            val goal = estimate.goal
            if (goal == null) {
                Text("You've reached the top level!", style = MaterialTheme.typography.titleMedium)
            } else {
                Text("Next goal: ${goal.label}", style = MaterialTheme.typography.titleMedium)
                LinearProgressIndicator(progress = { estimate.goalProgress }, modifier = Modifier.fillMaxWidth())
                Text(
                    "You know ${(estimate.goalWords * 100).roundToInt()}% of ${goal.label} words and " +
                        "${(estimate.goalGrammar * 100).roundToInt()}% of its grammar. " +
                        "Reach ${(KnowledgeRules.LEVEL_PASSED_FRACTION * 100).roundToInt()}% of both, or pass the ${goal.label} checkpoint, to level up.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                "${snapshot.knownWords} words known · ${snapshot.studiedWords} studied",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Your level comes from what you know and the checkpoints you passed, not from the level you study from.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
            )
        }
    }
}

@Composable
private fun Legend() {
    val colors = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LegendItem(colors.primary, "Mature")
        LegendItem(colors.tertiary, "Known")
        LegendItem(colors.secondary, "Learning")
    }
}

@Composable
private fun LegendItem(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun VocabRow(level: LevelProgress) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("N${level.jlpt}", style = MaterialTheme.typography.titleMedium)
                if (level.checkpointPassed) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "checkpoint passed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "${level.knownCount} / ${level.total}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            StackedBar(level.mature, level.known, level.learning, level.total)
        }
    }
}

/** Mature, known and learning words as parts of a bar the size of the level; the rest is unlearned. */
@Composable
private fun StackedBar(mature: Int, known: Int, learning: Int, total: Int) {
    val colors = MaterialTheme.colorScheme
    val rest = (total - mature - known - learning).coerceAtLeast(0)
    Row(
        Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(colors.surfaceVariant),
    ) {
        if (mature > 0) Box(Modifier.weight(mature.toFloat()).fillMaxHeight().background(colors.primary))
        if (known > 0) Box(Modifier.weight(known.toFloat()).fillMaxHeight().background(colors.tertiary))
        if (learning > 0) Box(Modifier.weight(learning.toFloat()).fillMaxHeight().background(colors.secondary))
        if (rest > 0) Spacer(Modifier.weight(rest.toFloat()))
    }
}

@Composable
private fun KanjiCard(kanji: List<KanjiProgress>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            kanji.forEach { level ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("N${level.jlpt}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(40.dp))
                    LinearProgressIndicator(
                        progress = { if (level.total == 0) 0f else level.known.toFloat() / level.total },
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${level.known} / ${level.total}",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.width(76.dp).padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun GrammarCard(levels: List<LevelProgress>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            levels.forEach { level ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("N${level.jlpt}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(40.dp))
                    LinearProgressIndicator(progress = { level.grammarFraction.toFloat() }, modifier = Modifier.weight(1f))
                    Text(
                        "${level.grammarKnown} / ${level.grammarTotal}",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.width(76.dp).padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun WeakWordRow(weak: WeakWord, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(weak.word.expression, style = MaterialTheme.typography.titleLarge.japanese())
                    if (weak.word.reading != weak.word.expression) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            weak.word.reading,
                            style = MaterialTheme.typography.bodyMedium.japanese(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(weak.word.meanings, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(forgottenLabel(weak.lapses), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                weak.retrievability?.let {
                    Text("${(it * 100).roundToInt()}% recall now", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

internal fun forgottenLabel(lapses: Int) = if (lapses == 1) "Forgotten once" else "Forgotten $lapses times"

@Composable
private fun Forecast(counts: List<Int>) {
    val max = counts.maxOrNull() ?: 0
    // The study day starts at 4 a.m., so between midnight and 4 a.m. "today" is still yesterday's date.
    val today = LocalDateTime.now().minusHours(4).toLocalDate()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                counts.forEachIndexed { index, count ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$count", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(4.dp))
                        Box(
                            Modifier
                                .width(20.dp)
                                .height((4f + if (max == 0) 0f else 60f * count / max).dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (count > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (index == 0) "Today" else today.plusDays(index.toLong()).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            Text(
                "${counts.sum()} cards due in the next ${counts.size} days (today includes anything overdue).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
