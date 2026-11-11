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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.flurai.app.core.auth.uid
import com.flurai.app.core.content.ContentDao
import com.flurai.app.core.content.WordEntity
import com.flurai.app.core.study.DeckRepository
import com.flurai.app.core.study.StudySource
import com.flurai.app.ui.DeckRoute
import com.flurai.app.ui.components.BackTopAppBar
import com.flurai.app.ui.components.LoadingBox
import com.flurai.app.ui.theme.japanese
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DeckUiState(
    val loading: Boolean = true,
    val name: String = "",
    val words: List<WordEntity> = emptyList(),
    /** The deck no longer exists (it was deleted). */
    val gone: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DeckViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val auth: AuthRepository,
    private val decks: DeckRepository,
    content: ContentDao,
) : ViewModel() {
    val deckId = savedStateHandle.toRoute<DeckRoute>().id

    val state: StateFlow<DeckUiState> = auth.currentUser.flatMapLatest { user ->
        if (user == null) return@flatMapLatest emptyFlow()
        combine(decks.decks(user.uid), decks.wordIds(deckId)) { all, ids ->
            val deck = all.firstOrNull { it.id == deckId }
                ?: return@combine DeckUiState(loading = false, gone = true)
            val byId = ids.chunked(500).flatMap { content.words(it) }.associateBy { it.id }
            DeckUiState(loading = false, name = deck.name, words = ids.mapNotNull { byId[it] })
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeckUiState())

    fun rename(name: String) {
        val uid = auth.uid ?: return
        viewModelScope.launch { decks.rename(uid, deckId, name) }
    }

    fun delete() {
        val uid = auth.uid ?: return
        viewModelScope.launch { decks.delete(uid, deckId) }
    }

    fun remove(wordId: Int) {
        viewModelScope.launch { decks.removeWord(deckId, wordId) }
    }
}

@Composable
fun DeckScreen(
    onBack: () -> Unit,
    onStudy: (StudySource) -> Unit,
    onOpenWord: (Int) -> Unit,
    onAddWords: (Long) -> Unit,
    viewModel: DeckViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(state.gone) { if (state.gone) onBack() }

    Column(Modifier.fillMaxSize()) {
        BackTopAppBar(
            title = { Text(state.name) },
            onBack = onBack,
            actions = {
                IconButton(onClick = { renaming = true }) { Icon(Icons.Filled.Edit, contentDescription = "Rename deck") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = "Delete deck") }
            },
        )
        if (state.loading || state.gone) return@Column LoadingBox()
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Button(
                    onClick = { onStudy(StudySource.Deck(viewModel.deckId)) },
                    enabled = state.words.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Study this deck") }
            }
            item {
                OutlinedButton(onClick = { onAddWords(viewModel.deckId) }, modifier = Modifier.fillMaxWidth()) { Text("Add words") }
            }
            if (state.words.isEmpty()) {
                item {
                    Text(
                        "This deck is empty. Choose Add words, or open a word (from Reading or a test) and choose Add to a deck.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                item {
                    Text(
                        if (state.words.size == 1) "1 word" else "${state.words.size} words",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(state.words, key = { it.id }) { word ->
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onOpenWord(word.id) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Row(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(word.expression, style = MaterialTheme.typography.titleMedium.japanese())
                            if (word.reading != word.expression) {
                                Text(word.reading, style = MaterialTheme.typography.bodySmall.japanese(), color = MaterialTheme.colorScheme.secondary)
                            }
                            Text(word.meanings, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        }
                        IconButton(onClick = { viewModel.remove(word.id) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove from deck")
                        }
                    }
                }
            }
        }
    }

    if (renaming) {
        NameDialog("Rename deck", state.name, "Save", onDismiss = { renaming = false }) {
            renaming = false
            viewModel.rename(it)
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this deck?") },
            text = { Text("Only the list is deleted. The words keep their flashcards and progress.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.delete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
