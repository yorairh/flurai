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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
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
        cells.forEachIndexed { index, cell ->
            val tappable = cell.word != null && onWord != null
            // A reading wider than its kanji may reach over a neighbor that has no reading of its own.
            val leftFree = index > 0 && (cells[index - 1].ruby == null || cells[index - 1].tail.isNotEmpty())
            val rightFree = cell.tail.isNotEmpty() || (index < cells.lastIndex && cells[index + 1].ruby == null)
            Row(if (tappable) Modifier.clickable { onWord?.invoke(cell.word!!) } else Modifier) {
                RubyCell(cell.text, cell.ruby, main, ruby, rubyColor, underline = tappable, leftFree = leftFree, rightFree = rightFree)
                if (cell.tail.isNotEmpty()) RubyCell(cell.tail, null, main, ruby, rubyColor, underline = false)
            }
        }
    }
}

/**
 * One piece of a sentence on screen that never breaks across lines: a word with kanji and its reading (or a
 * katakana word, or one character) and its [tail], the kana and closing punctuation that stay with it.
 */
internal class RubyCellData(val text: String, val ruby: String?, val word: WordEntity?, val tail: String = "")

/** Kana that stay with the kanji before them when a line breaks (okurigana and a particle): at most this many. */
internal const val KANA_TAIL = 2

internal const val CLOSING = "。、．，！？!?」』）)…"

internal fun isHiragana(c: Char) = c in 'ぁ'..'ゟ'
internal fun isKatakana(c: Char) = c in 'ァ'..'ヿ' && c != '・'

/**
 * Splits [parts] into cells so a line never breaks inside a word: a kanji word keeps up to [KANA_TAIL] kana that
 * follow it (投げ|る is one piece), a katakana word is one piece, and closing punctuation sticks to the piece
 * before it, so a lone 。 never lands on a line of its own. With [tokens], kana only join the word they belong to.
 */
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
                val word = wordAt[offset + k]
                val last = cells.lastOrNull()
                val tailKana = last?.tail.orEmpty().count { !it.isClosing() }
                when {
                    c.isClosing() && last != null -> cells[cells.lastIndex] = RubyCellData(last.text, last.ruby, last.word, last.tail + c)
                    // okurigana and a particle stay with the kanji word before them
                    isHiragana(c) && last != null && last.ruby != null && last.tail.none { it.isClosing() } &&
                        tailKana < KANA_TAIL && last.word == word ->
                        cells[cells.lastIndex] = RubyCellData(last.text, last.ruby, last.word, last.tail + c)
                    // a katakana word is one piece
                    isKatakana(c) && last != null && last.ruby == null && last.tail.isEmpty() && last.text.all(::isKatakana) && last.word == word ->
                        cells[cells.lastIndex] = RubyCellData(last.text + c, null, last.word)
                    else -> cells += RubyCellData(c.toString(), null, word)
                }
            }
        }
        offset += part.text.length
    }
    return cells
}

private fun Char.isClosing() = this in CLOSING

/** The base text's line height: close to the font size, so a paragraph with readings is not much looser than one without. */
internal fun tightLineHeight(style: TextStyle): TextUnit =
    if (style.fontSize.isSpecified) style.fontSize * 1.25f else style.lineHeight

/**
 * A reading stacked over its text. The cell is as wide as the text; a reading wider than that reaches over the
 * neighbor on a side that is [leftFree] / [rightFree] (has no reading of its own), at most one text width, and
 * widens the cell by what does not fit. Without this a long reading over one kanji leaves a gap on both sides.
 */
@Composable
internal fun RubyStack(
    reading: String?,
    rubyStyle: TextStyle,
    rubyColor: Color,
    leftFree: Boolean,
    rightFree: Boolean,
    modifier: Modifier = Modifier,
    reserveLine: Boolean = true,
    base: @Composable () -> Unit,
) {
    Layout(
        content = {
            // A space keeps the line, so cells without a reading line up with the ones that have it
            // (unless the caller bottom-aligns its cells and wants plain words to take no line at all).
            if (reading != null || reserveLine) Text(reading ?: " ", style = rubyStyle, color = rubyColor, maxLines = 1, softWrap = false)
            base()
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val loose = Constraints(0, constraints.maxWidth, 0, Constraints.Infinity)
        if (measurables.size == 1) {
            val only = measurables[0].measure(loose)
            return@Layout layout(only.width, only.height) { only.place(0, 0) }
        }
        val r = measurables[0].measure(loose)
        val b = measurables[1].measure(loose)
        val extra = (r.width - b.width).coerceAtLeast(0)
        val half = extra / 2
        val leftOver = if (leftFree) minOf(half, (b.width * 0.6f).toInt()) else 0
        val rightOver = if (rightFree) minOf(extra - half, (b.width * 0.6f).toInt()) else 0
        val leftPad = half - leftOver
        val rightPad = extra - half - rightOver
        val width = b.width + leftPad + rightPad
        layout(width, r.height + b.height) {
            r.place(leftPad + (b.width - r.width) / 2, 0)
            b.place(leftPad, r.height)
        }
    }
}

@Composable
private fun RubyCell(
    text: String,
    reading: String?,
    main: TextStyle,
    ruby: TextStyle,
    rubyColor: Color,
    underline: Boolean,
    leftFree: Boolean = false,
    rightFree: Boolean = false,
) {
    RubyStack(reading, ruby, rubyColor, leftFree, rightFree) {
        Text(
            text,
            style = main.copy(lineHeight = tightLineHeight(main)),
            textDecoration = if (underline) TextDecoration.Underline else null,
            maxLines = 1,
            softWrap = false,
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

/**
 * One piece of a mixed text on screen that never breaks across lines: an English word (with the spaces after
 * it), or a Japanese word (a kanji word with its reading, a katakana word, or one character) with its [tail]:
 * the kana, closing punctuation and glued English that stay with it.
 */
internal class MixedCell(val text: String, val ruby: String?, val bold: Boolean, val tail: String = "") {
    val full: String get() = text + tail
}

private fun isLatin(c: Char) = c.code < 0x2E80 && c != '＿'

/**
 * Splits furigana markup that may contain English, `**bold**` and Japanese into cells: an English word stays
 * whole (so it wraps by word); a Japanese word is one cell: a kanji word with its reading keeps the [KANA_TAIL]
 * kana after it (投げ|る), a katakana word is one cell, other Japanese is one cell per character; closing
 * punctuation sticks to the cell before it, and English written straight after Japanese ("う-row") stays with it.
 * The markers `**` only switch bold and are not drawn.
 */
internal fun mixedCells(markup: String): List<MixedCell> {
    val cells = mutableListOf<MixedCell>()
    var bold = false
    var word = StringBuilder()
    var wordBold = false
    // true while English written straight after a Japanese cell is being glued to it
    var glue = false
    fun flushWord() {
        if (word.isNotEmpty()) cells += MixedCell(word.toString(), null, wordBold).also { word = StringBuilder() }
    }
    fun lastIsJapanese() = cells.lastOrNull()?.let { it.ruby != null || !isLatin(it.text.last()) } == true
    fun appendToTail(c: Char) {
        cells[cells.lastIndex] = cells.last().let { MixedCell(it.text, it.ruby, it.bold, it.tail + c) }
    }
    for (part in parseRuby(markup)) {
        if (part.ruby != null) {
            flushWord()
            glue = false
            cells += MixedCell(part.text, part.ruby, bold)
            continue
        }
        val pieces = part.text.split("**")
        pieces.forEachIndexed { index, piece ->
            if (index > 0) { flushWord(); bold = !bold; glue = false }
            for (c in piece) {
                val last = cells.lastOrNull()
                when {
                    // a space after a bold switch belongs to the word before it, so a line never starts with one
                    c == ' ' && !glue && word.isEmpty() && last != null && last.ruby == null && isLatin(last.text.last()) && last.text.last() != ' ' ->
                        cells[cells.lastIndex] = MixedCell(last.text + c, null, last.bold)
                    // English straight after Japanese, up to the next space ("う-row"), stays with the Japanese
                    glue && c != ' ' && isLatin(c) -> appendToTail(c)
                    glue && c == ' ' -> { appendToTail(c); glue = false }
                    c != ' ' && isLatin(c) && word.isEmpty() && lastIsJapanese() && last != null && last.bold == bold && last.tail.none { it in CLOSING } && last.tail.lastOrNull() != ' ' -> {
                        glue = true
                        appendToTail(c)
                    }
                    isLatin(c) -> {
                        // spaces end a word but stay with it
                        if (word.isNotEmpty() && word.last() == ' ' && c != ' ') flushWord()
                        if (word.isEmpty()) wordBold = bold
                        else if (wordBold != bold && c != ' ') { flushWord(); wordBold = bold }
                        word.append(c)
                    }
                    c in CLOSING && last != null && word.isEmpty() -> appendToTail(c)
                    // okurigana and a particle stay with the kanji word before them
                    isHiragana(c) && word.isEmpty() && last != null && last.ruby != null && last.bold == bold &&
                        last.tail.none { it in CLOSING } && last.tail.length < KANA_TAIL -> appendToTail(c)
                    // a katakana word is one piece
                    isKatakana(c) && word.isEmpty() && last != null && last.ruby == null && last.tail.isEmpty() &&
                        last.bold == bold && last.text.all(::isKatakana) ->
                        cells[cells.lastIndex] = MixedCell(last.text + c, null, last.bold)
                    else -> { flushWord(); glue = false; cells += MixedCell(c.toString(), null, bold) }
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
        itemVerticalAlignment = Alignment.Bottom,
    ) {
        cells.forEachIndexed { index, cell ->
            // A word with a reading gets a tight line under it; plain words keep the style's own line, so a line
            // without readings is as tall as in a normal paragraph.
            val withReading = cell.ruby != null
            val cellStyle = (if (cell.bold) style.copy(fontWeight = FontWeight.Bold) else style)
                .let { if (withReading) it.copy(lineHeight = tightLineHeight(style)) else it }
            val leftFree = index > 0 && (cells[index - 1].ruby == null || cells[index - 1].tail.isNotEmpty())
            val rightFree = cell.tail.isNotEmpty() || (index < cells.lastIndex && cells[index + 1].ruby == null)
            Row(verticalAlignment = Alignment.Bottom) {
                RubyStack(cell.ruby, small, smallColor, leftFree, rightFree, reserveLine = false) {
                    Text(cell.text, style = cellStyle, color = color, maxLines = 1, softWrap = false)
                }
                if (cell.tail.isNotEmpty()) {
                    RubyStack(null, small, smallColor, leftFree = false, rightFree = false, reserveLine = false) {
                        Text(cell.tail, style = cellStyle, color = color, maxLines = 1, softWrap = false)
                    }
                }
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
