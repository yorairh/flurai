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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.flurai.app.core.quiz.firstGloss
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
                "Titles whose difficulty suits ${state.level.label}, most popular first.",
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
                    Text("How much you know", style = MaterialTheme.typography.titleMedium)
                    val analysis = state.analysis
                    when {
                        state.loading -> CircularProgressIndicator()
                        state.error != null -> {
                            Text(state.error.orEmpty())
                            TextButton(onClick = { viewModel.load() }) { Text("Try again") }
                        }
                        analysis != null -> {
                            val c = analysis.coverage
                            val share = (c.occurrenceShare * 100).roundToInt()
                            Text("About $share% of what is said", style = MaterialTheme.typography.headlineSmall)
                            LinearProgressIndicator(progress = { c.occurrenceShare.toFloat() }, modifier = Modifier.fillMaxWidth())
                            Text(
                                "You know ${c.knownWords} of the ${c.checkedWords} most common words. Only words from the JLPT lists " +
                                    "you study can count as known, so the real figure is a little higher.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (analysis.learnable.isNotEmpty()) {
                                Text("Words to learn first", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                                analysis.learnable.forEach { word ->
                                    Text(
                                        "${word.expression}${if (word.reading != word.expression) "（${word.reading}）" else ""}  ${firstGloss(word.meanings)}",
                                        style = MaterialTheme.typography.bodyMedium.japanese(),
                                    )
                                }
                                Button(onClick = { viewModel.addWords() }, modifier = Modifier.fillMaxWidth()) {
                                    Text("Learn these ${analysis.learnable.size} words first")
                                }
                                OutlinedButton(onClick = { viewModel.saveAsDeck() }, modifier = Modifier.fillMaxWidth(), enabled = state.savedDeck == null) {
                                    Text("Save them as a deck")
                                }
                            }
                            if (analysis.skippedHarder > 0 && !state.includeHarder) {
                                Text(
                                    "${analysis.skippedHarder} common words that are well above your level were left out.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                TextButton(onClick = { viewModel.setIncludeHarder(true) }) { Text("Include harder words") }
                            } else if (state.includeHarder) {
                                TextButton(onClick = { viewModel.setIncludeHarder(false) }) { Text("Only words near my level") }
                            }
                        }
                    }
                    state.savedDeck?.let { Text("Saved as the deck \"$it\" (Learn → Flashcards → My decks).", style = MaterialTheme.typography.bodyMedium) }
                    state.added?.let { Text("Added $it words to your flashcards.", style = MaterialTheme.typography.bodyMedium) }
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
