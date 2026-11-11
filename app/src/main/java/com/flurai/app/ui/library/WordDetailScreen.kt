package com.flurai.app.ui.library

import kotlinx.coroutines.flow.map
import com.flurai.app.ui.study.DeckPickerDialog
import com.flurai.app.core.study.DeckRepository
import com.flurai.app.core.study.DeckEntity
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.OutlinedButton
import com.flurai.app.core.auth.uid
import com.flurai.app.ui.components.ExampleSentence
import com.flurai.app.ui.components.LoadingBox
import com.flurai.app.ui.components.MissingVoiceBanner
import com.flurai.app.ui.components.SentenceSpeakButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.flurai.app.core.auth.AuthRepository
import com.flurai.app.core.speech.JapaneseSpeaker
import com.flurai.app.core.study.CardEntity
import com.flurai.app.core.study.ItemRef
import com.flurai.app.core.study.ItemType
import com.flurai.app.core.study.StudyRepository
import com.flurai.app.ui.WordRoute
import com.flurai.app.ui.study.YourCards
import com.flurai.app.ui.theme.japanese
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

data class WordDetail(val word: WordEntity, val sentences: List<SentenceEntity>)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class WordDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    content: ContentDao,
    private val auth: AuthRepository,
    private val study: StudyRepository,
    val speaker: JapaneseSpeaker,
    private val decks: DeckRepository,
) : ViewModel() {
    private val item = ItemRef(ItemType.Word, savedStateHandle.toRoute<WordRoute>().id)
    private val _detail = MutableStateFlow<WordDetail?>(null)
    val detail: StateFlow<WordDetail?> = _detail.asStateFlow()

    /** This user's cards for the word (empty when not studying it yet). */
    val cards: StateFlow<List<CardEntity>> = auth.currentUser
        .flatMapLatest { user -> if (user == null) flowOf(emptyList()) else study.cardsForItem(user.uid, item) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The learner's decks, and which of them contain this word. */
    val deckList: StateFlow<List<DeckEntity>> = auth.currentUser
        .flatMapLatest { user -> if (user == null) flowOf(emptyList()) else decks.decks(user.uid) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val inDecks: StateFlow<Set<Long>> = auth.currentUser
        .flatMapLatest { user -> if (user == null) flowOf(emptySet()) else decks.decksWithWord(user.uid, item.itemId).map { it.toSet() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    init {
        speaker.start()
        viewModelScope.launch {
            val word = content.word(item.itemId) ?: return@launch
            _detail.value = WordDetail(word, content.sentencesForWord(item.itemId))
        }
    }

    fun speak() {
        _detail.value?.let { speaker.speak(it.word.reading) }
    }

    fun speakSentence(text: String, slow: Boolean) = speaker.speak(text, slow)

    private fun withUid(action: suspend (String) -> Unit) {
        val uid = auth.uid ?: return
        viewModelScope.launch { action(uid) }
    }

    fun setInDeck(deckId: Long, inDeck: Boolean) {
        viewModelScope.launch { if (inDeck) decks.addWord(deckId, item.itemId) else decks.removeWord(deckId, item.itemId) }
    }

    fun createDeckWithWord(name: String) = withUid { decks.create(it, name, listOf(item.itemId)) }

    fun startLearning() = withUid { study.addToStudy(it, item, Instant.now()) }
    fun setSuspended(suspended: Boolean) = withUid { study.setSuspended(it, item, suspended) }
    fun reset() = withUid { study.reset(it, item) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WordDetailScreen(onBack: () -> Unit, viewModel: WordDetailViewModel = hiltViewModel()) {
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val cards by viewModel.cards.collectAsStateWithLifecycle()
    val deckList by viewModel.deckList.collectAsStateWithLifecycle()
    val inDecks by viewModel.inDecks.collectAsStateWithLifecycle()
    var pickingDeck by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {},
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            },
            // The app Scaffold already pads for the status bar.
            windowInsets = WindowInsets(0),
        )
        MissingVoiceBanner(viewModel.speaker)
        val d = detail ?: return LoadingBox()
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(word.expression, style = MaterialTheme.typography.displayMedium.japanese(), modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(12.dp))
                FilledTonalIconButton(onClick = viewModel::speak) { Icon(Icons.Filled.PlayArrow, contentDescription = "Play audio") }
            }
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

            YourCards(
                cards = cards,
                onStart = viewModel::startLearning,
                onSuspend = viewModel::setSuspended,
                onReset = viewModel::reset,
            )
            OutlinedButton(onClick = { pickingDeck = true }, modifier = Modifier.fillMaxWidth()) {
                Text(if (inDecks.isEmpty()) "Add to a deck" else "In ${inDecks.size} of your decks · change")
            }

            Text("Examples", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            if (d.sentences.isEmpty()) {
                Text("No example sentences yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            d.sentences.forEach { ExampleCard(it, onSpeak = { slow -> viewModel.speakSentence(it.japanese, slow) }) }
            Text(
                "Examples from Tatoeba (CC BY 2.0 FR).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
    if (pickingDeck) {
        DeckPickerDialog(
            decks = deckList,
            selected = inDecks,
            onToggle = viewModel::setInDeck,
            onCreate = viewModel::createDeckWithWord,
            onDismiss = { pickingDeck = false },
        )
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
private fun ExampleCard(sentence: SentenceEntity, onSpeak: (slow: Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ExampleSentence(sentence.japanese, MaterialTheme.typography.titleMedium.japanese())
                Text(sentence.english, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            SentenceSpeakButton(onSpeak = onSpeak)
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
