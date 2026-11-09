package com.flurai.app.ui.reading

import com.flurai.app.core.model.JlptNumbers
import com.flurai.app.ui.components.BackTopAppBar
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flurai.app.core.content.WordEntity
import com.flurai.app.ui.theme.japanese


/** Outside reading for each level: link only, because these texts are not ours to copy. */
private val externalLinks = listOf(
    Triple("NHK News Web Easy", "News in simple Japanese with furigana (N4 to N3).", "https://www3.nhk.or.jp/news/easy/"),
    Triple("Tadoku free readers", "Graded readers from absolute beginner upward.", "https://tadoku.org/japanese/en/free-books-en/"),
    Triple("Watanoc", "Easy articles about Japan with levels from N5 to N3.", "https://watanoc.com/"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingScreen(
    onBack: () -> Unit,
    onCheck: (jlpt: Int) -> Unit,
    onOpenWord: (Int) -> Unit,
    viewModel: ReadingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        BackTopAppBar(title = { Text("Reading") }, onBack = onBack)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                JlptNumbers.forEach { n ->
                    FilterChip(selected = state.jlpt == n, onClick = { viewModel.chooseLevel(n) }, label = { Text("N$n", maxLines = 1) })
                }
            }
            Text(
                "Tap an underlined word to see its meaning or add it to your flashcards.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Show translations", modifier = Modifier.weight(1f))
                Switch(checked = state.showTranslations, onCheckedChange = { viewModel.toggleTranslations() })
            }

            if (state.loading) {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            } else if (state.lines.isEmpty()) {
                Text("No sentences found for this level yet.")
            } else {
                state.lines.forEach { line ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            val linkStyle = TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline))
                            Text(
                                buildAnnotatedString {
                                    line.tokens.forEach { token ->
                                        val word = token.word
                                        if (word == null) append(token.text) else {
                                            withLink(LinkAnnotation.Clickable("w${word.id}", linkStyle) { viewModel.select(word) }) {
                                                append(token.text)
                                            }
                                        }
                                    }
                                },
                                style = MaterialTheme.typography.titleLarge.japanese(),
                            )
                            if (state.showTranslations) {
                                Text(
                                    line.english,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { viewModel.newSet() }, modifier = Modifier.weight(1f)) { Text("New set") }
                Button(onClick = { onCheck(state.jlpt) }, modifier = Modifier.weight(1f)) { Text("Check yourself") }
            }

            Text("More to read", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            externalLinks.forEach { (title, body, url) ->
                Card(
                    onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text(
                "Sentences come from Tatoeba. These sites open in your browser.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
        }
    }

    state.selected?.let { word ->
        WordSheet(
            word = word,
            added = word.id in state.addedWordIds,
            onAdd = { viewModel.addToFlashcards(word) },
            onDetails = { viewModel.select(null); onOpenWord(word.id) },
            onDismiss = { viewModel.select(null) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WordSheet(word: WordEntity, added: Boolean, onAdd: () -> Unit, onDetails: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(word.expression, style = MaterialTheme.typography.displaySmall.japanese())
            if (word.reading != word.expression) Text(word.reading, style = MaterialTheme.typography.titleLarge.japanese())
            Text(word.meanings, style = MaterialTheme.typography.bodyLarge)
            Text("N${word.jlpt}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Button(onClick = onAdd, enabled = !added, modifier = Modifier.fillMaxWidth()) {
                Text(if (added) "Added to flashcards" else "Add to flashcards")
            }
            TextButton(onClick = onDetails, modifier = Modifier.fillMaxWidth()) { Text("Full details and examples") }
        }
    }
}
