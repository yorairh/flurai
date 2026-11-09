package com.flurai.app.ui.progress

import com.flurai.app.ui.components.LoadingBox
import com.flurai.app.ui.components.BackTopAppBar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flurai.app.core.model.JlptNumbers
import com.flurai.app.ui.study.cardStatus
import com.flurai.app.ui.theme.japanese

/** The words the learner has started, filtered by how well they're known. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordBrowserScreen(
    onBack: () -> Unit,
    onOpenWord: (Int) -> Unit,
    viewModel: WordBrowserViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        BackTopAppBar(title = { Text("Your words") }, onBack = onBack)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(BrowserFilter.entries) { filter ->
                FilterChip(
                    selected = state.filter == filter,
                    onClick = { viewModel.selectFilter(filter) },
                    label = { Text("${filter.label} · ${state.counts[filter] ?: 0}") },
                )
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                FilterChip(selected = state.level == null, onClick = { viewModel.selectLevel(null) }, label = { Text("All levels") })
            }
            items(JlptNumbers) { n ->
                FilterChip(selected = state.level == n, onClick = { viewModel.selectLevel(n) }, label = { Text("N$n") })
            }
        }

        when {
            state.loading -> LoadingBox()
            state.rows.isEmpty() -> Text(
                emptyMessage(state.filter, state.counts[BrowserFilter.All] ?: 0),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                items(state.rows, key = { it.word.id }) { row ->
                    BrowserWordRow(row) { onOpenWord(row.word.id) }
                    HorizontalDivider(Modifier.padding(start = 16.dp))
                }
            }
        }
    }
}

internal fun emptyMessage(filter: BrowserFilter, allCount: Int): String = when {
    allCount == 0 -> "You haven't started any words here yet. Study some in Learn → Flashcards."
    filter == BrowserFilter.Suspended -> "No suspended words."
    else -> "No ${filter.label.lowercase()} words here yet."
}

@Composable
private fun BrowserWordRow(row: BrowserRow, onClick: () -> Unit) {
    val word = row.word
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(word.expression, style = MaterialTheme.typography.titleLarge.japanese())
                if (word.reading != word.expression) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        word.reading,
                        style = MaterialTheme.typography.bodyMedium.japanese(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(word.meanings, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text("N${word.jlpt}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(cardStatus(row.card), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
