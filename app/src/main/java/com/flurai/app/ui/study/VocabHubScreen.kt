package com.flurai.app.ui.study

import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.OutlinedButton
import com.flurai.app.ui.components.BackTopAppBar
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flurai.app.core.study.KanaScript
import com.flurai.app.core.study.StudySource
import com.flurai.app.ui.components.SectionHeader
import com.flurai.app.core.model.JlptNumbers
import com.flurai.app.ui.theme.japanese

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VocabHubScreen(
    onBack: () -> Unit,
    onStudy: (StudySource) -> Unit,
    onOpenDeck: (Long) -> Unit,
    onOpenSource: (StudySource) -> Unit,
    viewModel: VocabHubViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        BackTopAppBar(title = { Text("Flashcards") }, onBack = onBack)
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { TodayCard(state, onStudy = { onStudy(StudySource.Auto) }) }
            item { SectionHeader("自作", "My decks", "Your own word lists. Add words from a word's page.") }
            items(state.myDecks, key = { it.id }) { deck ->
                DeckRow(deck.name, deck.progress) { onOpenDeck(deck.id) }
            }
            item {
                OutlinedButton(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) { Text("New deck") }
            }
            item { SectionHeader("かな", "Kana decks", "Hiragana and katakana, sound by sound. Open one to study just it.") }
            items(KanaScript.entries) { script ->
                state.kana[script]?.let { DeckRow("${script.japanese}  ${script.label}", it) { onOpenSource(StudySource.KanaDeck(script)) } }
            }
            item { SectionHeader("単語", "Word decks", "Open a level to see its words and study just it.") }
            items(JlptNumbers) { n ->
                state.levels[n]?.let { DeckRow("N$n", it) { onOpenSource(StudySource.Level(n)) } }
            }
        }
    }
    if (creating) {
        NameDialog("New deck", "", "Create", onDismiss = { creating = false }) {
            creating = false
            viewModel.createDeck(it)
        }
    }
}

@Composable
private fun TodayCard(state: VocabHubState, onStudy: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("今日", style = MaterialTheme.typography.titleMedium.japanese())
            Row {
                Stat(state.due, "to review", Modifier.weight(1f))
                Stat(state.newLeftToday, "new today", Modifier.weight(1f))
            }
            Button(
                onClick = onStudy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            ) { Text(if (state.due + state.newLeftToday == 0) "Check for cards" else "Study now") }
        }
    }
}

@Composable
private fun Stat(value: Int, label: String, modifier: Modifier) {
    Column(modifier) {
        Text("$value", style = MaterialTheme.typography.displaySmall)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DeckRow(name: String, progress: DeckProgress, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.titleMedium.japanese(), modifier = Modifier.weight(1f))
                Text(
                    "${progress.studying} / ${progress.total}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            LinearProgressIndicator(
                progress = { if (progress.total == 0) 0f else progress.studying.toFloat() / progress.total },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
