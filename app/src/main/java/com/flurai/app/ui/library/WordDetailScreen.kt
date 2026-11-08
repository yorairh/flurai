package com.flurai.app.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.toRoute
import com.flurai.app.core.content.ContentDao
import com.flurai.app.core.content.SentenceEntity
import com.flurai.app.core.content.WordEntity
import com.flurai.app.ui.WordRoute
import com.flurai.app.ui.theme.japanese
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WordDetail(val word: WordEntity, val sentences: List<SentenceEntity>)

@HiltViewModel
class WordDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    content: ContentDao,
) : ViewModel() {
    private val _detail = MutableStateFlow<WordDetail?>(null)
    val detail: StateFlow<WordDetail?> = _detail.asStateFlow()

    init {
        val id = savedStateHandle.toRoute<WordRoute>().id
        viewModelScope.launch {
            val word = content.word(id) ?: return@launch
            _detail.value = WordDetail(word, content.sentencesForWord(id))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WordDetailScreen(onBack: () -> Unit, viewModel: WordDetailViewModel = hiltViewModel()) {
    val detail by viewModel.detail.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {},
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            },
            // The app Scaffold already pads for the status bar.
            windowInsets = WindowInsets(0),
        )
        val d = detail ?: return Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        val word = d.word
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (word.reading != word.expression) {
                Text(word.reading, style = MaterialTheme.typography.titleMedium.japanese(), color = MaterialTheme.colorScheme.secondary)
            }
            Text(word.expression, style = MaterialTheme.typography.displayMedium.japanese())
            word.otherForms?.let {
                Text(
                    "Also written $it",
                    style = MaterialTheme.typography.bodyMedium.japanese(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(word.meanings, style = MaterialTheme.typography.titleLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Tag("JLPT N${word.jlpt}")
                word.genkiLesson?.let { Tag("Genki lesson $it") }
                partOfSpeechLabel(word.partsOfSpeech)?.let { Tag(it) }
            }

            Text("Examples", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            if (d.sentences.isEmpty()) {
                Text("No example sentences yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            d.sentences.forEach { ExampleCard(it) }
            Text(
                "Examples from Tatoeba (CC BY 2.0 FR).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun Tag(text: String) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun ExampleCard(sentence: SentenceEntity) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(sentence.japanese, style = MaterialTheme.typography.titleMedium.japanese())
            Text(sentence.english, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A readable label for the main JMdict part-of-speech code, e.g. "v5r,vt" → "Godan verb". */
fun partOfSpeechLabel(codes: String?): String? {
    val first = codes?.split(",")?.firstOrNull()?.trim() ?: return null
    return when {
        first == "v1" -> "Ichidan verb"
        first.startsWith("v5") -> "Godan verb"
        first == "vs" || first == "vs-i" || first == "vs-s" -> "する verb"
        first == "vk" -> "くる verb"
        first == "adj-i" || first == "adj-ix" -> "い-adjective"
        first == "adj-na" -> "な-adjective"
        first == "adj-no" -> "の-adjective"
        first == "adv" || first == "adv-to" -> "Adverb"
        first == "n" -> "Noun"
        first == "pn" -> "Pronoun"
        first == "int" -> "Interjection"
        first == "conj" -> "Conjunction"
        first == "prt" -> "Particle"
        first == "exp" -> "Expression"
        first == "ctr" -> "Counter"
        first == "suf" -> "Suffix"
        first == "pref" -> "Prefix"
        else -> null
    }
}
