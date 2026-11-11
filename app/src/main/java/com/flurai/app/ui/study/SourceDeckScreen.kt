package com.flurai.app.ui.study

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.flurai.app.core.auth.AuthRepository
import com.flurai.app.core.content.ContentDao
import com.flurai.app.core.study.ItemRef
import com.flurai.app.core.study.ItemType
import com.flurai.app.core.study.KanaData
import com.flurai.app.core.study.KanaScript
import com.flurai.app.core.study.Kana
import androidx.compose.foundation.layout.Spacer
import com.flurai.app.core.study.StudyRepository
import com.flurai.app.core.study.StudySource
import com.flurai.app.ui.SourceDeckRoute
import com.flurai.app.ui.components.BackTopAppBar
import com.flurai.app.ui.components.LoadingBox
import com.flurai.app.ui.theme.japanese
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** One line of a built-in deck: a word or a kana, and whether it has been started. */
data class SourceItemRow(val key: Long, val primary: String, val secondary: String?, val detail: String?, val started: Boolean, val wordId: Int?)

data class SourceDeckState(
    val loading: Boolean = true,
    val title: String = "",
    val rows: List<SourceItemRow> = emptyList(),
) {
    val started: Int get() = rows.count { it.started }
}

/** A built-in deck (an N level or a kana script): its items, your progress, and a button to study just it. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SourceDeckViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    auth: AuthRepository,
    study: StudyRepository,
    private val content: ContentDao,
) : ViewModel() {
    val source: StudySource = StudySource.decode(savedStateHandle.toRoute<SourceDeckRoute>().source)

    private suspend fun items(): Pair<String, List<SourceItemRow>> = when (val s = source) {
        is StudySource.Level -> {
            val ids = content.wordIdsInStudyOrder(s.jlpt)
            val byId = ids.chunked(500).flatMap { content.words(it) }.associateBy { it.id }
            "N${s.jlpt} words" to ids.mapNotNull { byId[it] }.map {
                SourceItemRow(it.id.toLong(), it.expression, it.reading.takeIf { r -> r != it.expression }, it.meanings, false, it.id)
            }
        }
        is StudySource.KanaDeck -> "${s.script.japanese} ${s.script.label}" to KanaData.of(s.script).map {
            SourceItemRow(it.id.toLong(), it.kana, it.romaji, null, false, null)
        }
        else -> "" to emptyList()
    }

    val state: StateFlow<SourceDeckState> = auth.currentUser.flatMapLatest { user ->
        if (user == null) return@flatMapLatest emptyFlow()
        val (title, rows) = items()
        val isKana = source is StudySource.KanaDeck
        study.introducedItems(user.uid).map { introduced ->
            val type = if (isKana) ItemType.Kana else ItemType.Word
            val started = introduced.filter { it.itemType == type }.map(ItemRef::itemId).toSet()
            SourceDeckState(false, title, rows.map { it.copy(started = it.key.toInt() in started) })
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SourceDeckState())
}

@Composable
fun SourceDeckScreen(
    onBack: () -> Unit,
    onStudy: (StudySource) -> Unit,
    onOpenWord: (Int) -> Unit,
    viewModel: SourceDeckViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        BackTopAppBar(title = { Text(state.title) }, onBack = onBack)
        if (state.loading) return@Column LoadingBox()
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onStudy(viewModel.source) },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) { Text("Study this deck") }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${state.started} of ${state.rows.size} started",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    LinearProgressIndicator(
                        progress = { if (state.rows.isEmpty()) 0f else state.started.toFloat() / state.rows.size },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            val script = (viewModel.source as? StudySource.KanaDeck)?.script
            if (script != null) {
                item { KanaChart(script, started = state.rows.filter { it.started }.map { it.key.toInt() }.toSet()) }
            }
            items(if (script != null) emptyList() else state.rows, key = { it.key }) { row ->
                Card(
                    modifier = Modifier.fillMaxWidth().let { m -> if (row.wordId != null) m.clickable { onOpenWord(row.wordId) } else m },
                    colors = CardDefaults.cardColors(
                        containerColor = if (row.started) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(row.primary, style = MaterialTheme.typography.titleMedium.japanese(), modifier = Modifier.weight(1f))
                            if (row.started) Text("started", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        row.secondary?.let { Text(it, style = MaterialTheme.typography.bodySmall.japanese(), color = MaterialTheme.colorScheme.secondary) }
                        row.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1) }
                    }
                }
            }
        }
    }
}

/** The kana as a chart, a row per consonant; the ones you have started are shaded. */
@Composable
private fun KanaChart(script: KanaScript, started: Set<Int>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        KanaData.chart(script).forEach { section ->
            Text(section.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            section.rows.forEach { row ->
                val columns = if (row.size == 1) 5 else row.size
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { kana -> KanaCell(kana, kana != null && kana.id in started, Modifier.weight(1f)) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun KanaCell(kana: Kana?, started: Boolean, modifier: Modifier) {
    if (kana == null) return Spacer(modifier)
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (started) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(kana.kana, style = MaterialTheme.typography.titleLarge.japanese())
            Text(kana.romaji, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
