package com.flurai.app.ui.tests

import com.flurai.app.ui.components.StudyClockEffects
import androidx.compose.material3.Icon
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import com.flurai.app.ui.components.LoadingBox
import com.flurai.app.ui.components.BackTopAppBar
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import com.flurai.app.ui.components.ExampleSentence
import com.flurai.app.ui.components.FuriganaText
import com.flurai.app.ui.components.LocalFurigana
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.flurai.app.core.model.JlptLevel
import com.flurai.app.core.quiz.MissedQuestion
import com.flurai.app.core.quiz.Question
import com.flurai.app.core.quiz.QuizResult
import com.flurai.app.core.quiz.QuizRunState
import com.flurai.app.core.quiz.QuestionKind
import com.flurai.app.core.quiz.QuizKind
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.mutableStateOf
import com.flurai.app.ui.theme.japanese

private val CorrectGreen = Color(0xFF3C9A4E)
private val WrongRed = Color(0xFFD9483B)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuizScreen(
    onBack: () -> Unit,
    onTakeCheckpoint: (Int) -> Unit,
    onRetry: (kind: String) -> Unit,
    onOpenLesson: (Int) -> Unit,
    viewModel: QuizViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    StudyClockEffects(onResume = viewModel::onResume, onPause = viewModel::onPause)

    // Leaving in the middle of a test loses its answers, so it asks first.
    var confirmLeave by remember { mutableStateOf(false) }
    val inProgress = state.result == null && state.error == null && (state.number > 1 || state.selected != null)
    BackHandler(enabled = inProgress) { confirmLeave = true }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Leave this test?") },
            text = { Text("Your answers so far will not be saved.") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; onBack() }) { Text("Leave") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Keep going") } },
        )
    }

    Column(Modifier.fillMaxSize()) {
        BackTopAppBar(title = { Text(state.title) }, onBack = { if (inProgress) confirmLeave = true else onBack() }, backDescription = "Close")
        val question = state.question
        val result = state.result
        when {
            state.error != null -> Message(state.error.orEmpty(), onBack)
            state.loading -> LoadingBox()
            result != null -> ResultView(result, viewModel, onBack, onTakeCheckpoint, { onRetry(viewModel.kind.encode()) }, onOpenLesson)
            question != null -> QuestionView(state, question, viewModel::choose, viewModel::arrange, viewModel::next, viewModel::replay)
        }
    }
}

@Composable
private fun Message(text: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onBack) { Text("Back") }
    }
}

@Composable
private fun QuestionView(
    state: QuizRunState,
    question: Question,
    onChoose: (Int) -> Unit,
    onArrange: (List<Int>) -> Unit,
    onNext: () -> Unit,
    onReplay: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
        val total = state.total
        if (total != null) {
            LinearProgressIndicator(progress = { (state.number - 1f) / total }, modifier = Modifier.fillMaxWidth())
            Text("Question ${state.number} of $total", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        } else {
            Text("Question ${state.number}", style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(24.dp))
        val course = question.kind == QuestionKind.CourseChoice || question.kind == QuestionKind.CourseTap || question.kind == QuestionKind.CourseOrder
        if (course) {
            question.instruction?.let {
                QText(it, question, MaterialTheme.typography.titleMedium, Modifier.fillMaxWidth(), centered = true)
                Spacer(Modifier.height(12.dp))
            }
        }
        if (question.kind == QuestionKind.WordListening) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                FilledTonalIconButton(onClick = onReplay, modifier = Modifier.size(96.dp)) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Play the word again", modifier = Modifier.size(56.dp))
                }
            }
        } else if (question.kind != QuestionKind.CourseTap && question.prompt.isNotBlank()) {
            val promptStyle = (
                when {
                    course -> MaterialTheme.typography.headlineSmall
                    question.kind == QuestionKind.GrammarBlank || question.kind == QuestionKind.SentenceMeaning -> MaterialTheme.typography.headlineMedium
                    else -> MaterialTheme.typography.displayMedium
                }
                ).japanese()
            if (!course && question.furigana && (question.kind == QuestionKind.GrammarBlank || question.kind == QuestionKind.SentenceMeaning)) {
                // a whole sentence: the readings come from the app's own word list
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ExampleSentence(question.prompt, null, promptStyle) }
            } else {
                QText(question.prompt, question, promptStyle, Modifier.fillMaxWidth(), centered = true)
            }
        }
        question.hint?.let {
            QText(
                it, question, MaterialTheme.typography.titleMedium.japanese(),
                Modifier.fillMaxWidth().padding(top = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant, centered = true,
            )
        }
        if (question.kind != QuestionKind.KanjiReading && !course) {
            Text(
                when (question.kind) {
                    QuestionKind.GrammarBlank -> "Which part fits the gap?"
                    QuestionKind.WordListening -> "What does the word you heard mean?"
                    else -> "What does it mean?"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        when (question.kind) {
            QuestionKind.CourseTap -> TapSentence(question, state.selected, onChoose)
            QuestionKind.CourseOrder -> OrderTiles(question, state.selected != null, state.arranged, onArrange)
            else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                question.options.forEachIndexed { index, option ->
                    OptionButton(option, index, question, state.selected, onChoose)
                }
            }
        }
        if (state.selected != null) {
            question.translation?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyLarge,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
            question.note?.let {
                QText(
                    it, question, MaterialTheme.typography.bodyMedium.japanese(),
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onNext, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Next") }
        }
    }
}

/** Tap the one word of a sentence that answers the question. */
@Composable
private fun TapSentence(question: Question, selected: Int?, onChoose: (Int) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        question.options.forEachIndexed { index, word ->
            val color = when {
                selected == null -> null
                index == question.answer -> CorrectGreen
                index == selected -> WrongRed
                else -> null
            }
            androidx.compose.material3.Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                color = color ?: MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = if (color != null) Color.White else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.androidxClickable(enabled = selected == null) { onChoose(index) },
            ) {
                QText(word, question, MaterialTheme.typography.headlineSmall.japanese(), Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
    }
}

private fun Modifier.androidxClickable(enabled: Boolean, onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(enabled = enabled, onClick = onClick))

/** Build the sentence from its pieces: tap a piece to place it, tap a placed piece to take it back, then check. */
@Composable
private fun OrderTiles(question: Question, answered: Boolean, arranged: String?, onArrange: (List<Int>) -> Unit) {
    val tiles = question.tiles.orEmpty()
    var placed by remember(question) { mutableStateOf(listOf<Int>()) }
    val right = answered && arranged != null && question.isArrangementRight(arranged)
    val border = when {
        !answered -> MaterialTheme.colorScheme.outline
        right -> CorrectGreen
        else -> WrongRed
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        androidx.compose.material3.Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = androidx.compose.foundation.BorderStroke(1.dp, border),
            modifier = Modifier.fillMaxWidth(),
        ) {
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(10.dp),
            ) {
                placed.forEach { i -> Tile(tiles[i], question) { if (!answered) placed = placed - i } }
            }
        }
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            tiles.indices.filter { it !in placed }.forEach { i -> Tile(tiles[i], question) { if (!answered) placed = placed + i } }
        }
        if (!answered) {
            Button(onClick = { onArrange(placed) }, enabled = placed.size == tiles.size, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Check") }
        } else {
            val correct = question.correctText.orEmpty()
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (right) {
                    Text("Correct", style = MaterialTheme.typography.titleMedium, color = CorrectGreen)
                } else {
                    Text("Right: ", style = MaterialTheme.typography.titleMedium, color = WrongRed)
                    QText(correct, question, MaterialTheme.typography.titleMedium.japanese(), color = WrongRed)
                }
            }
        }
    }
}

@Composable
private fun Tile(text: String, question: Question, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) { QText(text, question, MaterialTheme.typography.titleMedium.japanese()) }
}

/** A text of [question] with the furigana the database has for it, unless the setting is off or the question tests the reading. */
@Composable
private fun QText(
    text: String,
    question: Question,
    style: androidx.compose.ui.text.TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    centered: Boolean = false,
) = FuriganaText(text, question.ruby[text], style, modifier, color, show = LocalFurigana.current && question.furigana, centered = centered)

@Composable
private fun OptionButton(option: String, index: Int, question: Question, selected: Int?, onChoose: (Int) -> Unit) {
    val revealed = selected != null
    val color = when {
        !revealed -> null
        index == question.answer -> CorrectGreen
        index == selected -> WrongRed
        else -> null
    }
    if (color == null) {
        OutlinedButton(onClick = { onChoose(index) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            QText(option, question, MaterialTheme.typography.titleMedium.japanese(), centered = true)
        }
    } else {
        Button(
            onClick = {},
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
        ) { QText(option, question, MaterialTheme.typography.titleMedium.japanese(), centered = true) }
    }
}

@Composable
private fun MissedList(missed: List<MissedQuestion>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("What you missed", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        missed.forEach { m ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val show = LocalFurigana.current && m.furigana
                    FuriganaLine("", m.prompt, m.ruby[m.prompt], MaterialTheme.typography.titleMedium.japanese(), Color.Unspecified, show)
                    m.hint?.let { FuriganaLine("", it, m.ruby[it], MaterialTheme.typography.bodySmall.japanese(), MaterialTheme.colorScheme.onSurfaceVariant, show) }
                    FuriganaLine("Right: ", m.correct, m.ruby[m.correct], MaterialTheme.typography.bodyMedium.japanese(), CorrectGreen, show)
                    FuriganaLine("You chose: ", m.chosen, m.ruby[m.chosen], MaterialTheme.typography.bodyMedium.japanese(), WrongRed, show)
                    m.translation?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    m.note?.let { FuriganaLine("", it, m.ruby[it], MaterialTheme.typography.bodySmall.japanese(), MaterialTheme.colorScheme.onSurfaceVariant, show) }
                }
            }
        }
    }
}

@Composable
private fun ResultView(
    result: QuizResult,
    viewModel: QuizViewModel,
    onBack: () -> Unit,
    onTakeCheckpoint: (Int) -> Unit,
    onRetry: () -> Unit,
    onOpenLesson: (Int) -> Unit,
) {
    val lessonTest = viewModel.kind is QuizKind.CourseLesson
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text("結果", style = MaterialTheme.typography.titleMedium.japanese(), color = MaterialTheme.colorScheme.secondary)
        Text("${result.correct} / ${result.total}", style = MaterialTheme.typography.displayLarge)
        result.passed?.let { passed ->
            Text(
                when {
                    lessonTest && passed -> "Lesson passed!"
                    lessonTest -> "Not yet: ${kotlin.math.ceil(result.total * 0.8).toInt()} right out of ${result.total} passes the lesson. Look over what you missed, then try again."
                    passed -> "Passed! You know ${result.title.substringBefore(' ')} well."
                    else -> "Not yet. Keep studying and try again."
                },
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
        result.placementLevel?.let { level ->
            Text(
                if (level == JlptLevel.Beginner) "A good place to start: the basics." else "A good place to start: ${level.label}.",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                "This is a suggestion and does not change your level. Your level goes up when you pass a level checkpoint, " +
                    "or know 80% of a level's words and grammar.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            level.jlpt?.let { jlpt ->
                Button(onClick = { onTakeCheckpoint(jlpt) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Take the ${level.label} checkpoint")
                }
            }
            if (level != result.currentLevel) {
                if (result.levelApplied) {
                    Text("Words now come from ${level.label}.", color = MaterialTheme.colorScheme.primary)
                } else {
                    OutlinedButton(onClick = viewModel::applyPlacementLevel, modifier = Modifier.fillMaxWidth()) {
                        Text("Study new words from ${level.label}")
                    }
                }
            }
        }
        if (result.missedNewWordIds.isNotEmpty() || result.missedAdded) {
            if (result.missedAdded) {
                Text("Added ${result.missedNewWordIds.size} words to your flashcards.", color = MaterialTheme.colorScheme.primary)
            } else {
                OutlinedButton(onClick = viewModel::addMissedToFlashcards, modifier = Modifier.fillMaxWidth()) {
                    Text("Add ${result.missedNewWordIds.size} missed ${if (result.missedNewWordIds.size == 1) "word" else "words"} to flashcards")
                }
            }
        } else if (result.missedWordIds.isNotEmpty()) {
            Text("The words you missed are already in your flashcards.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        if (result.missed.isNotEmpty()) MissedList(result.missed)
        if (lessonTest) {
            result.nextLessonId?.takeIf { result.passed == true }?.let { next ->
                Button(onClick = { onOpenLesson(next) }, modifier = Modifier.fillMaxWidth()) { Text("Next lesson") }
            }
            OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
        }
        TextButton(onClick = onBack) { Text(if (lessonTest) "Back to the lesson" else "Done") }
    }
}

/** One line of the missed list: an optional label, then the text with its furigana. */
@Composable
private fun FuriganaLine(label: String, text: String, ruby: String?, style: androidx.compose.ui.text.TextStyle, color: Color, show: Boolean) {
    if (label.isEmpty()) {
        FuriganaText(text, ruby, style, color = color, show = show)
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = style, color = color)
            FuriganaText(text, ruby, style, color = color, show = show)
        }
    }
}
