package com.flurai.app.ui.explore

import com.flurai.app.ui.components.BackTopAppBar
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import com.flurai.app.ui.components.StudyProgressBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.flurai.app.core.media.ImportOptions
import com.flurai.app.core.media.ImportTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.flurai.app.core.media.Deck
import com.flurai.app.core.media.MediaType
import com.flurai.app.core.model.JlptLevel
import com.flurai.app.ui.theme.japanese
import kotlin.math.roundToInt

private fun openLink(context: Context, url: String) {
    if (url.startsWith("https://") || url.startsWith("http://")) context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
}

private val levelChoices = listOf(JlptLevel.N5, JlptLevel.N4, JlptLevel.N3, JlptLevel.N2, JlptLevel.N1)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaScreen(onBack: () -> Unit, onOpen: (Int) -> Unit, viewModel: MediaViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    // Load the next page when the end of the list comes into view.
    LaunchedEffect(listState, state.decks.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (last >= state.decks.size - 3) viewModel.loadMore()
        }
    }
    Column(Modifier.fillMaxSize()) {
        BackTopAppBar(title = { Text("Anime, shows & manga") }, onBack = onBack)
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MediaType.entries.forEach { type ->
                    FilterChip(selected = state.type == type, onClick = { viewModel.chooseType(type) }, label = { Text(type.label, maxLines = 1) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                levelChoices.forEach { level ->
                    val selected = state.level == level || (state.level == JlptLevel.Beginner && level == JlptLevel.N5)
                    FilterChip(selected = selected, onClick = { viewModel.chooseLevel(level) }, label = { Text(level.label, maxLines = 1) })
                }
            }
            Text(
                "Titles with a difficulty close to ${state.level.label}, most popular first. Even easy shows use thousands of words, so open one to see how much you would understand.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.decks, key = { it.deckId }) { deck -> DeckRow(deck) { onOpen(deck.deckId) } }
            item {
                Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    when {
                        state.loading -> CircularProgressIndicator()
                        state.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(state.error.orEmpty(), textAlign = TextAlign.Center)
                            TextButton(onClick = { viewModel.retry() }) { Text("Try again") }
                        }
                        state.decks.isEmpty() -> Text("No titles found for this level.")
                        else -> Text(
                            "Data from jiten.moe",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeckRow(deck: Deck, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Cover(deck, Modifier.width(64.dp).height(90.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(deck.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                if (deck.originalTitle.isNotBlank() && deck.originalTitle != deck.title) {
                    Text(deck.originalTitle, style = MaterialTheme.typography.bodyMedium.japanese(), maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "Difficulty ${"%.1f".format(deck.difficultyRaw)} of 5 · ${deck.uniqueWordCount} different words",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun Cover(deck: Deck, modifier: Modifier) {
    val shape = RoundedCornerShape(8.dp)
    val url = deck.coverName?.takeIf { it.startsWith("https://") }
    if (url == null) {
        Box(modifier.clip(shape).then(Modifier), contentAlignment = Alignment.Center) { Text("🎬") }
    } else {
        AsyncImage(model = url, contentDescription = null, modifier = modifier.clip(shape), contentScale = ContentScale.Crop)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaDetailScreen(onBack: () -> Unit, viewModel: MediaDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val deck = state.deck
    var showSheet by rememberSaveable { mutableStateOf(false) }
    if (showSheet) {
        ImportSheet(
            state = state,
            onOptions = viewModel::setOptions,
            onStudyFromDeck = viewModel::setStudyFromDeck,
            onImport = { viewModel.importDeck(); showSheet = false },
            onDismiss = { showSheet = false },
        )
    }
    Column(Modifier.fillMaxSize()) {
        BackTopAppBar(title = { Text(deck?.title ?: "Title") }, onBack = onBack)
        if (deck == null) {
            Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Open this title from the list again.", textAlign = TextAlign.Center)
                Button(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) { Text("Back") }
            }
            return@Column
        }
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row {
                Cover(deck, Modifier.width(96.dp).height(135.dp))
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(deck.title, style = MaterialTheme.typography.titleLarge)
                    if (deck.originalTitle != deck.title) Text(deck.originalTitle, style = MaterialTheme.typography.bodyLarge.japanese())
                    Text(
                        "${MediaType.of(deck.mediaType)?.label ?: "Media"} · difficulty ${"%.1f".format(deck.difficultyRaw)} of 5",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        "${deck.uniqueWordCount} different words · ${deck.wordCount} words in total",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Words from this title", style = MaterialTheme.typography.titleMedium)
                    val analysis = state.analysis
                    when {
                        state.loading -> {
                            CircularProgressIndicator()
                            Text("Getting all the words…", style = MaterialTheme.typography.bodyMedium)
                        }
                        state.error != null -> {
                            Text(state.error.orEmpty())
                            TextButton(onClick = { viewModel.load() }) { Text("Try again") }
                        }
                        analysis != null -> {
                            val p = analysis.progress
                            Text("You know ${p.knownWords} of ${p.listedWords} words", style = MaterialTheme.typography.headlineSmall)
                            StudyProgressBar(progress = { p.share.toFloat() }, modifier = Modifier.fillMaxWidth())
                            Text(
                                "Counts the different words of this title found in the dictionary.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (analysis.words.isNotEmpty()) {
                                Button(onClick = { showSheet = true }, modifier = Modifier.fillMaxWidth()) {
                                    Text(if (state.existingDeck == null) "Make a deck from this title" else "Add more words to its deck")
                                }
                            }
                        }
                    }
                    state.imported?.let { done ->
                        val what = if (done.isNew) "Made the deck \"${done.name}\" with ${done.total} words"
                        else "Added ${done.added} words to \"${done.name}\" (${done.total} in all)"
                        val study = if (state.studyingFromImported) " New words now come from this deck." else ""
                        Text("$what (Learn → Flashcards → My decks).$study", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            deck.description?.takeIf { it.isNotBlank() }?.let {
                Text(it.take(600), style = MaterialTheme.typography.bodyMedium)
            }
            deck.links.filter { it.url.startsWith("http") }.take(4).forEach { link ->
                OutlinedButton(onClick = { openLink(context, link.url) }, modifier = Modifier.fillMaxWidth()) {
                    Text(linkLabel(link.linkType))
                }
            }
            Text(
                "Difficulty and word data from jiten.moe (CC BY-SA). Cover images belong to their owners.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }
}

/** The choices for a deck made from a title: how many words, which to leave out, and in what order. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ImportSheet(
    state: MediaDetailState,
    onOptions: (ImportOptions) -> Unit,
    onStudyFromDeck: (Boolean) -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    val o = state.options
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(if (state.existingDeck == null) "Make a deck" else "Add to \"${state.existingDeck}\"", style = MaterialTheme.typography.titleLarge)
            Text("Words to understand", style = MaterialTheme.typography.titleSmall)
            Text(
                "Cover a share of the title's text with its most frequent words, or take a number of them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ImportTarget.entries.forEach { target ->
                    FilterChip(selected = o.target == target, onClick = { onOptions(o.copy(target = target)) }, label = { Text(target.label) })
                }
            }
            Text("Leave out", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            val harder = JlptLevel.entries.getOrNull(state.studyLevel.ordinal + 2)
            SwitchRow("Words I know", o.skipKnown) { onOptions(o.copy(skipKnown = it)) }
            SwitchRow("Words that appear only once", o.skipSeenOnce) { onOptions(o.copy(skipSeenOnce = it)) }
            SwitchRow("Words written only in kana", o.skipKanaOnly) { onOptions(o.copy(skipKanaOnly = it)) }
            if (harder != null) {
                SwitchRow("JLPT words of ${harder.label} and harder", o.skipTooHard) { onOptions(o.copy(skipTooHard = it)) }
            }
            Text("Order", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !o.storyOrder, onClick = { onOptions(o.copy(storyOrder = false)) }, label = { Text("Most frequent first") })
                FilterChip(selected = o.storyOrder, onClick = { onOptions(o.copy(storyOrder = true)) }, label = { Text("Story order") })
            }
            SwitchRow("Take my daily new words from this deck", state.studyFromDeck, onStudyFromDeck)
            val count = state.picked.size
            Button(onClick = onImport, enabled = count > 0 && !state.importing, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(
                    when {
                        count == 0 -> "No words left with these choices"
                        state.existingDeck == null -> "Make a deck of $count words"
                        else -> "Add up to $count words"
                    },
                )
            }
            Text(
                "Word list from jiten.moe (CC BY-SA). No cards are started: the deck's words come up as new words.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun linkLabel(type: Int) = when (type) {
    1 -> "Website"
    2 -> "VNDB"
    3 -> "TMDB"
    4 -> "AniList"
    5 -> "MyAnimeList"
    6 -> "Google Books"
    7 -> "IMDb"
    8 -> "IGDB"
    9 -> "Syosetu"
    10 -> "Bookmeter"
    11 -> "Amazon"
    12 -> "YouTube"
    13 -> "Kakuyomu"
    else -> "More info"
}
