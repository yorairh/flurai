package com.flurai.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
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
import com.flurai.app.core.reading.isKanji
import com.flurai.app.core.reading.Token
import com.flurai.app.core.reading.parseRuby
import com.flurai.app.core.reading.rubyParts
import com.flurai.app.core.reading.rubyPartsOf
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

/** The learner's "show furigana" setting, provided once for the whole app (on until it is known). */
val LocalFurigana = staticCompositionLocalOf { true }

/**
 * A sentence with the reading above each kanji word. Text cannot draw ruby by itself, so each piece is a
 * small two-line cell (reading over text) in a flow layout; kana and symbols are one cell per character,
 * so the sentence wraps like normal text. [parts] come from the furigana markup of the database. With
 * [tokens] and [onWord] the words you can tap are underlined and open [onWord]; without them the sentence
 * is only for reading.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RubySentence(parts: List<RubyPart>, main: TextStyle, tokens: List<Token> = emptyList(), onWord: ((WordEntity) -> Unit)? = null) {
    val ruby = MaterialTheme.typography.labelSmall.japanese().copy(fontSize = 10.sp, lineHeight = 11.sp, textAlign = TextAlign.Center)
    val rubyColor = MaterialTheme.colorScheme.onSurfaceVariant
    val text = parts.joinToString("") { it.text }
    val cells = remember(parts, tokens) { rubyCells(parts, tokens) }
    // The cells are drawn one by one, so a screen reader would read them one kana at a time: describe the sentence once instead.
    FlowRow(
        verticalArrangement = Arrangement.spacedBy(0.dp),
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = text
            // The tappable words stay reachable for a screen reader as actions.
            if (onWord != null) {
                customActions = cells.mapNotNull { c -> c.word?.let { w -> CustomAccessibilityAction("Look up ${c.text}") { onWord(w); true } } }
                    .distinctBy { it.label }
            }
        },
    ) {
        cells.forEach { cell ->
            val tappable = cell.word != null && onWord != null
            Row(if (tappable) Modifier.clickable { onWord?.invoke(cell.word!!) } else Modifier) {
                RubyCell(cell.text, cell.ruby, main, ruby, rubyColor, underline = tappable)
                cell.tail.forEach { RubyCell(it.toString(), null, main, ruby, rubyColor, underline = false) }
            }
        }
    }
}

/** One piece of a sentence on screen: a word with kanji and its reading, or a single character. */
internal class RubyCellData(val text: String, val ruby: String?, val word: WordEntity?, val tail: String = "")

/** Splits [parts] into cells; closing punctuation sticks to the cell before it, so a lone 。 never lands on a line of its own. */
internal fun rubyCells(parts: List<RubyPart>, tokens: List<Token>): List<RubyCellData> {
    // Which word (if any) covers each character, from the tokens.
    val wordAt = HashMap<Int, WordEntity>()
    var offset = 0
    tokens.forEach { token ->
        token.word?.let { w -> for (k in offset until offset + token.text.length) wordAt[k] = w }
        offset += token.text.length
    }
    val cells = mutableListOf<RubyCellData>()
    offset = 0
    parts.forEach { part ->
        if (part.ruby != null) {
            cells += RubyCellData(part.text, part.ruby, wordAt[offset])
        } else {
            part.text.forEachIndexed { k, c ->
                if (c in "。、．，！？!?」』）)…" && cells.isNotEmpty()) {
                    cells[cells.lastIndex] = cells.last().let { RubyCellData(it.text, it.ruby, it.word, it.tail + c) }
                } else {
                    cells += RubyCellData(c.toString(), null, wordAt[offset + k])
                }
            }
        }
        offset += part.text.length
    }
    return cells
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
 * An example sentence for reading, with furigana over its kanji unless the learner turned furigana off. [ruby] is the
 * markup from the database; without it the sentence is read with the app's own word list. [style] is the Japanese
 * text style (already `.japanese()`).
 */
@Composable
fun ExampleSentence(japanese: String, ruby: String?, style: TextStyle, modifier: Modifier = Modifier, viewModel: FuriganaViewModel = hiltViewModel()) {
    val show = LocalFurigana.current
    if (!show) return Text(japanese, style = style, modifier = modifier)
    if (ruby != null) return Box(modifier) { RubySentence(parseRuby(ruby), style) }
    val tokens: State<List<Token>?> = produceState<List<Token>?>(null, japanese) { value = viewModel.tokens(japanese) }
    val loaded = tokens.value
    if (loaded != null) Box(modifier) { RubySentence(rubyPartsOf(loaded), style) } else Text(japanese, style = style, modifier = modifier)
}


// -- mixed English / Japanese text ------------------------------------------------------------------

/** One piece of a mixed text on screen: an English word (with the spaces after it) or one Japanese character or kanji word. */
internal class MixedCell(val text: String, val ruby: String?, val bold: Boolean)

private fun isLatin(c: Char) = c.code < 0x2E80 && c != '＿'

/**
 * Splits furigana markup that may contain English, `**bold**` and Japanese into cells: an English word stays
 * whole (so it wraps by word), Japanese is one cell per character, a kanji word is one cell with its reading,
 * and closing punctuation sticks to the cell before it. The markers `**` only switch bold and are not drawn.
 */
internal fun mixedCells(markup: String): List<MixedCell> {
    val cells = mutableListOf<MixedCell>()
    var bold = false
    var word = StringBuilder()
    var wordBold = false
    fun flushWord() {
        if (word.isNotEmpty()) cells += MixedCell(word.toString(), null, wordBold).also { word = StringBuilder() }
    }
    for (part in parseRuby(markup)) {
        if (part.ruby != null) {
            flushWord()
            cells += MixedCell(part.text, part.ruby, bold)
            continue
        }
        val pieces = part.text.split("**")
        pieces.forEachIndexed { index, piece ->
            if (index > 0) { flushWord(); bold = !bold }
            for (c in piece) {
                when {
                    // a space after a bold switch belongs to the word before it, so a line never starts with one
                    c == ' ' && word.isEmpty() && cells.isNotEmpty() && cells.last().let { it.ruby == null && isLatin(it.text.last()) && it.text.last() != ' ' } ->
                        cells[cells.lastIndex] = cells.last().let { MixedCell(it.text + c, null, it.bold) }
                    isLatin(c) -> {
                        // spaces end a word but stay with it
                        if (word.isNotEmpty() && word.last() == ' ' && c != ' ') flushWord()
                        if (word.isEmpty()) wordBold = bold
                        else if (wordBold != bold && c != ' ') { flushWord(); wordBold = bold }
                        word.append(c)
                    }
                    c in "。、．，！？!?」』）)…" && cells.isNotEmpty() && word.isEmpty() ->
                        cells[cells.lastIndex] = cells.last().let { MixedCell(it.text + c, it.ruby, it.bold) }
                    else -> { flushWord(); cells += MixedCell(c.toString(), null, bold) }
                }
            }
        }
    }
    flushWord()
    return cells
}

/** The plain text of markup: no readings and no bold markers (for a screen reader). */
internal fun plainOf(markup: String): String = parseRuby(markup).joinToString("") { it.text }.replace("**", "")

/**
 * Text that mixes English and Japanese, with furigana over the kanji words. Without [ruby] markup (no kanji, or
 * the learner turned furigana off) it is a normal `Text`; `**bold**` is honored either way. [style] is the body
 * style (add `.japanese()` where the text is Japanese). Use it for lesson paragraphs, options, hints and notes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FuriganaText(
    text: String,
    ruby: String?,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    show: Boolean = LocalFurigana.current,
    centered: Boolean = false,
) {
    if (!show || ruby == null) {
        Text(boldText(text), style = style, color = color, modifier = modifier, textAlign = if (centered) TextAlign.Center else null)
        return
    }
    val cells = remember(ruby) { mixedCells(ruby) }
    val small = MaterialTheme.typography.labelSmall.japanese().copy(fontSize = 10.sp, lineHeight = 11.sp, textAlign = TextAlign.Center)
    val smallColor = (if (color == Color.Unspecified) LocalContentColor.current else color).copy(alpha = 0.75f)
    FlowRow(
        modifier.clearAndSetSemantics { contentDescription = plainOf(ruby) },
        horizontalArrangement = if (centered) Arrangement.Center else Arrangement.Start,
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        cells.forEach { cell ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(cell.ruby ?: " ", style = small, color = smallColor, maxLines = 1)
                Text(
                    cell.text,
                    style = if (cell.bold) style.copy(fontWeight = FontWeight.Bold) else style,
                    color = color,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/** [text] with **bold** markers turned into bold spans. */
fun boldText(text: String): AnnotatedString = buildAnnotatedString {
    text.split("**").forEachIndexed { index, part ->
        if (index % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(part) } else append(part)
    }
}


// -- words ---------------------------------------------------------------------------------------------------

/** True when [word] is drawn with its reading over the kanji (furigana on and the word has kanji), so no separate reading line is needed. */
@Composable
fun wordHasRuby(word: WordEntity): Boolean =
    LocalFurigana.current && word.reading != word.expression && word.expression.any(::isKanji)

/** A word's spelling; with furigana on, the reading sits over its kanji. Where it returns [wordHasRuby], the reading line is left out. */
@Composable
fun WordExpression(word: WordEntity, style: TextStyle, modifier: Modifier = Modifier) {
    if (wordHasRuby(word)) {
        Box(modifier) { RubySentence(remember(word.id, word.reading) { rubyParts(word.expression, word.reading) }, style) }
    } else {
        Text(word.expression, style = style, modifier = modifier)
    }
}
