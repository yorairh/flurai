package com.flurai.app.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flurai.app.core.content.KanjiEntity
import com.flurai.app.core.content.WordEntity
import com.flurai.app.ui.theme.japanese

/** Browse and search the built-in N5–N1 words and kanji. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onBack: () -> Unit,
    onOpenWord: (Int) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val tab by viewModel.tab.collectAsStateWithLifecycle()
    val jlpt by viewModel.jlpt.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val words by viewModel.words.collectAsStateWithLifecycle()
    val kanji by viewModel.kanji.collectAsStateWithLifecycle()
    val wordCounts by viewModel.wordCounts.collectAsStateWithLifecycle()
    val kanjiCounts by viewModel.kanjiCounts.collectAsStateWithLifecycle()
    var selectedKanji by rememberSaveable { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Word library") },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            },
            // The app Scaffold already pads for the status bar.
            windowInsets = WindowInsets(0),
        )
        PrimaryTabRow(selectedTabIndex = tab.ordinal) {
            LibraryTab.entries.forEach {
                Tab(selected = tab == it, onClick = { viewModel.selectTab(it) }, text = { Text(it.name) })
            }
        }

        val searching = tab == LibraryTab.Words && query.isNotBlank()
        if (tab == LibraryTab.Words) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::search,
                placeholder = { Text("Search: 食べる, たべる or eat") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.search("") }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                textStyle = MaterialTheme.typography.bodyLarge.japanese(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (!searching) {
            val counts = if (tab == LibraryTab.Words) wordCounts else kanjiCounts
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(JlptNumbers) { n ->
                    FilterChip(
                        selected = jlpt == n,
                        onClick = { viewModel.selectLevel(n) },
                        label = { Text(counts[n]?.let { "N$n · $it" } ?: "N$n") },
                    )
                }
            }
        }

        Box(Modifier.weight(1f)) {
            when (tab) {
                LibraryTab.Words -> WordList(words, searching, onOpenWord)
                LibraryTab.Kanji -> KanjiGrid(kanji) { selectedKanji = it.literal }
            }
        }
    }

    val shown = kanji?.find { it.literal == selectedKanji }
    if (shown != null) KanjiSheet(shown) { selectedKanji = null }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun WordList(words: List<WordEntity>?, searching: Boolean, onOpenWord: (Int) -> Unit) {
    if (words == null) return Loading()
    if (words.isEmpty()) {
        Text(
            if (searching) "No words found." else "No words at this level.",
            modifier = Modifier.padding(24.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        items(words, key = { it.id }) { word ->
            WordRow(word, showLevel = searching) { onOpenWord(word.id) }
            HorizontalDivider(Modifier.padding(start = 16.dp))
        }
    }
}

@Composable
private fun WordRow(word: WordEntity, showLevel: Boolean, onClick: () -> Unit) {
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
            Text(
                word.meanings,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showLevel) {
            Spacer(Modifier.width(8.dp))
            Text("N${word.jlpt}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun KanjiGrid(kanji: List<KanjiEntity>?, onClick: (KanjiEntity) -> Unit) {
    if (kanji == null) return Loading()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(64.dp),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(kanji, key = { it.literal }) { k ->
            Card(
                onClick = { onClick(k) },
                modifier = Modifier.aspectRatio(1f),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(k.literal, style = MaterialTheme.typography.headlineMedium.japanese())
                }
            }
        }
    }
}
