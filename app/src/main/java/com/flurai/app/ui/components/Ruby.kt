package com.flurai.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.flurai.app.core.activity.SettingsRepository
import com.flurai.app.core.auth.AuthRepository
import com.flurai.app.core.content.WordEntity
import com.flurai.app.core.reading.ReadingRepository
import com.flurai.app.core.reading.RubyPart
import com.flurai.app.core.reading.Token
import com.flurai.app.core.reading.rubyParts
import com.flurai.app.ui.theme.japanese
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * A sentence with the reading above each kanji word. Text cannot draw ruby by itself, so each piece
 * is a small two-line cell (reading over text) in a flow layout; kana and symbols are one cell per
 * character, so the sentence wraps like normal text. When [onWord] is given, words you can tap are
 * underlined and open it; without it the sentence is only for reading.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RubySentence(tokens: List<Token>, main: TextStyle, onWord: ((WordEntity) -> Unit)? = null) {
    val ruby = MaterialTheme.typography.labelSmall.japanese().copy(fontSize = 10.sp, lineHeight = 11.sp, textAlign = TextAlign.Center)
    val rubyColor = MaterialTheme.colorScheme.onSurfaceVariant
    // Units that may wrap as a whole: a word, or one plain character. Closing punctuation
    // is stuck to the unit before it, so a lone 。 never lands on a line of its own.
    class Unit(val word: WordEntity?, val parts: List<RubyPart>, val tail: String = "")
    val units = mutableListOf<Unit>()
    tokens.forEach { token ->
        val word = token.word
        if (word != null) {
            units += Unit(word, rubyParts(token.text, word.reading))
        } else {
            token.text.forEach { c ->
                if (c in "。、．，！？!?」』）)…" && units.isNotEmpty()) units[units.lastIndex] = units.last().let { Unit(it.word, it.parts, it.tail + c) }
                else units += Unit(null, listOf(RubyPart(c.toString(), null)))
            }
        }
    }
    val sentence = tokens.joinToString("") { it.text }
    // The cells are drawn one by one, so a screen reader would read them one kana at a time: describe the sentence once instead.
    FlowRow(
        verticalArrangement = Arrangement.spacedBy(0.dp),
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = sentence
            // The tappable words stay reachable for a screen reader as actions.
            if (onWord != null) {
                customActions = units.mapNotNull { u ->
                    u.word?.let { w -> CustomAccessibilityAction("Look up ${u.parts.joinToString("") { it.text }}") { onWord(w); true } }
                }
            }
        },
    ) {
        units.forEach { u ->
            Row(if (u.word != null && onWord != null) Modifier.clickable { onWord(u.word) } else Modifier) {
                u.parts.forEach { RubyCell(it.text, it.ruby, main, ruby, rubyColor, underline = u.word != null && onWord != null) }
                u.tail.forEach { RubyCell(it.toString(), null, main, ruby, rubyColor, underline = false) }
            }
        }
    }
}

@Composable
private fun RubyCell(text: String, reading: String?, main: TextStyle, ruby: TextStyle, rubyColor: androidx.compose.ui.graphics.Color, underline: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // A space keeps the line, so cells without a reading line up with the ones that have it.
        Text(reading ?: " ", style = ruby, color = rubyColor, maxLines = 1)
        Text(
            text,
            style = main.copy(lineHeight = 26.sp),
            textDecoration = if (underline) TextDecoration.Underline else null,
        )
    }
}

@HiltViewModel
class FuriganaViewModel @Inject constructor(
    private val reading: ReadingRepository,
    auth: AuthRepository,
    settings: SettingsRepository,
) : ViewModel() {
    /** The learner's "show furigana" setting (on until it is known). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val showFurigana: StateFlow<Boolean> = auth.currentUser
        .flatMapLatest { user -> if (user == null) flowOf(true) else settings.settings(user.uid).map { it.showFurigana } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    suspend fun tokens(text: String): List<Token> = reading.lexicon().tokenize(text)
}

/**
 * An example sentence for reading, with furigana over its kanji words unless the learner turned
 * furigana off. [style] is the Japanese text style (already `.japanese()`).
 */
@Composable
fun ExampleSentence(japanese: String, style: TextStyle, modifier: Modifier = Modifier, viewModel: FuriganaViewModel = hiltViewModel()) {
    val show by viewModel.showFurigana.collectAsStateWithLifecycle()
    val tokens: State<List<Token>?> = produceState<List<Token>?>(null, japanese, show) {
        value = if (show) viewModel.tokens(japanese) else null
    }
    val loaded = tokens.value
    if (show && loaded != null) {
        androidx.compose.foundation.layout.Box(modifier) { RubySentence(loaded, style) }
    } else {
        Text(japanese, style = style, modifier = modifier)
    }
}
