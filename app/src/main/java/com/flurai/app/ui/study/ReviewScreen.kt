package com.flurai.app.ui.study

import com.flurai.app.ui.components.StudyClockEffects
import androidx.compose.foundation.layout.size
import com.flurai.app.ui.components.LoadingBox
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.PaddingValues
import com.flurai.app.core.study.StudySource
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.flurai.app.core.study.CardContent
import com.flurai.app.core.study.CurrentCard
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flurai.app.core.speech.JapaneseSpeaker
import com.flurai.app.core.srs.Rating
import com.flurai.app.core.study.CardKind
import com.flurai.app.core.study.QueueCounts
import com.flurai.app.ui.theme.japanese
import java.time.Duration
import java.time.Instant

// Anki's familiar count colours: new (blue), learning (red), review (green).
private val NewBlue = Color(0xFF3F7FD9)
private val LearnRed = Color(0xFFD9483B)
private val ReviewGreen = Color(0xFF3C9A4E)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(onBack: () -> Unit, viewModel: ReviewViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val speech by viewModel.speaker.status.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    StudyClockEffects(onResume = { viewModel.refresh(); viewModel.onResume() }, onPause = viewModel::onPause)

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { if (!state.finished) Counts(state.counts) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            },
            actions = {
                if (state.current != null) {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Hide until tomorrow") },
                            onClick = { menuOpen = false; viewModel.buryCurrent() },
                        )
                        DropdownMenuItem(
                            text = { Text("Suspend (stop showing)") },
                            onClick = { menuOpen = false; viewModel.suspendCurrent() },
                        )
                    }
                }
            },
            windowInsets = WindowInsets(0),
        )
        if (speech == JapaneseSpeaker.Status.MissingVoice) MissingVoiceBanner(viewModel)

        val current = state.current
        when {
            state.loading -> LoadingBox()
            state.finished -> Finished(
                state.answeredCount, state.comeBackAt, state.levelsWithWords, state.noMoreWords,
                deckSession = state.deckSession, deckHasMore = state.deckHasMore,
                onMoreDeck = { viewModel.learnMore() },
                onMore = viewModel::learnMore, onBack = onBack,
            )
            current != null -> {
                Box(Modifier.weight(1f)) {
                    CardBody(current, state.revealed, onSpeak = viewModel::speakCurrent)
                }
                AnswerBar(state.revealed, state.intervals, onReveal = viewModel::reveal, onAnswer = viewModel::answer)
            }
            else -> Box(Modifier.fillMaxSize()) // saving an answer
        }
    }
}

@Composable
private fun Counts(counts: QueueCounts) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("${counts.new}", color = NewBlue, fontWeight = FontWeight.Bold)
        Text("${counts.learning}", color = LearnRed, fontWeight = FontWeight.Bold)
        Text("${counts.review}", color = ReviewGreen, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MissingVoiceBanner(viewModel: ReviewViewModel) {
    val context = LocalContext.current
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "No Japanese voice on this phone, so cards are silent.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { runCatching { context.startActivity(viewModel.speaker.installVoiceIntent()) } }) {
                Text("Install")
            }
        }
    }
}

@Composable
private fun CardBody(card: CurrentCard, revealed: Boolean, onSpeak: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (card.isNew) {
            Surface(shape = MaterialTheme.shapes.small, color = NewBlue) {
                Text("New", color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
        when (val content = card.content) {
            is CardContent.KanaCard -> {
                Text(content.kana.kana, style = MaterialTheme.typography.displayLarge.japanese().copy(fontSize = 120.sp))
                if (revealed) {
                    HorizontalDivider()
                    Text(content.kana.romaji, style = MaterialTheme.typography.displayMedium)
                    SpeakButton(onSpeak)
                }
            }
            is CardContent.Word -> if (card.item.kind == CardKind.Listening) {
                Text("What do you hear?", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalIconButton(onClick = onSpeak, modifier = Modifier.size(96.dp)) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Play audio", modifier = Modifier.size(56.dp))
                }
                if (revealed) {
                    HorizontalDivider()
                    WordAnswer(content, showExpression = true, onSpeak = onSpeak, showMeaning = true)
                }
            } else if (card.item.kind == CardKind.Recognition) {
                Text(
                    content.word.expression,
                    style = MaterialTheme.typography.displayLarge.japanese().copy(fontSize = 56.sp),
                    textAlign = TextAlign.Center,
                )
                if (revealed) {
                    HorizontalDivider()
                    WordAnswer(content, showExpression = false, onSpeak = onSpeak)
                }
            } else {
                Text("How do you say…", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(content.word.meanings, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                if (revealed) {
                    HorizontalDivider()
                    WordAnswer(content, showExpression = true, onSpeak = onSpeak)
                }
            }
        }
    }
}

@Composable
private fun WordAnswer(content: CardContent.Word, showExpression: Boolean, onSpeak: () -> Unit, showMeaning: Boolean = false) {
    val word = content.word
    if (showExpression) {
        Text(word.expression, style = MaterialTheme.typography.displayMedium.japanese(), textAlign = TextAlign.Center)
    }
    if (word.reading != word.expression) {
        Text(word.reading, style = MaterialTheme.typography.headlineSmall.japanese(), color = MaterialTheme.colorScheme.secondary)
    }
    if (!showExpression || showMeaning) {
        Text(word.meanings, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    }
    SpeakButton(onSpeak)
    content.example?.let { example ->
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(example.japanese, style = MaterialTheme.typography.titleMedium.japanese())
                Text(example.english, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SpeakButton(onSpeak: () -> Unit) {
    FilledTonalIconButton(onClick = onSpeak) { Icon(Icons.Filled.PlayArrow, contentDescription = "Play audio") }
}

@Composable
private fun AnswerBar(
    revealed: Boolean,
    intervals: Map<Rating, String>,
    onReveal: () -> Unit,
    onAnswer: (Rating) -> Unit,
) {
    Surface(tonalElevation = 2.dp) {
        if (!revealed) {
            Button(
                onClick = onReveal,
                modifier = Modifier.fillMaxWidth().padding(16.dp).height(56.dp),
            ) { Text("Show answer") }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                RatingButton("Again", intervals[Rating.Again], LearnRed) { onAnswer(Rating.Again) }
                RatingButton("Hard", intervals[Rating.Hard], MaterialTheme.colorScheme.outline) { onAnswer(Rating.Hard) }
                RatingButton("Good", intervals[Rating.Good], ReviewGreen) { onAnswer(Rating.Good) }
                RatingButton("Easy", intervals[Rating.Easy], NewBlue) { onAnswer(Rating.Easy) }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.RatingButton(
    label: String,
    interval: String?,
    color: Color,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.weight(1f).height(64.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(interval.orEmpty(), style = MaterialTheme.typography.labelSmall)
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun Finished(
    answered: Int,
    comeBackAt: Instant?,
    levelsWithWords: Set<Int>,
    noMoreWords: Boolean,
    deckSession: Boolean,
    deckHasMore: Boolean,
    onMoreDeck: () -> Unit,
    onMore: (StudySource) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("お疲れさま！", style = MaterialTheme.typography.displaySmall.japanese(), color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                answered == 0 && comeBackAt == null -> "Nothing to study right now. You're all caught up!"
                answered == 0 -> "Nothing due right now."
                else -> "You answered $answered ${if (answered == 1) "card" else "cards"}."
            },
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        if (comeBackAt != null) {
            val minutes = Duration.between(Instant.now(), comeBackAt).toMinutes().coerceAtLeast(1)
            Text(
                "Some cards are still in learning. The next one is ready in about $minutes min; this screen updates when you come back.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        if (deckSession) {
            // A deck you chose only ever continues with itself.
            if (deckHasMore) {
                Button(onClick = onMoreDeck, modifier = Modifier.fillMaxWidth()) { Text("10 more words from this deck") }
            } else {
                Text("No new words left in this deck.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (levelsWithWords.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text("Want to learn more?", style = MaterialTheme.typography.titleMedium)
            if (noMoreWords) {
                Text("No new words left in that deck.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { onMore(StudySource.Auto) }, modifier = Modifier.fillMaxWidth()) { Text("10 more new words") }
            Spacer(Modifier.height(8.dp))
            Text("or pick a section", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                levelsWithWords.sortedDescending().forEach { n ->
                    OutlinedButton(
                        onClick = { onMore(StudySource.Level(n)) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 0.dp),
                    ) { Text("N$n", maxLines = 1, softWrap = false) }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onBack) { Text("Done") }
    }
}
