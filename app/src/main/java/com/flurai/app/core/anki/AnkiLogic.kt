package com.flurai.app.core.anki

import com.flurai.app.core.reading.isKanji
import com.flurai.app.core.srs.FsrsScheduler
import com.flurai.app.core.srs.Rating
import com.flurai.app.core.srs.SrsCard
import com.flurai.app.core.srs.SrsState
import com.flurai.app.core.study.CardKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Duration
import java.time.Instant

// The pure rules of an Anki import: cleaning a field, guessing what each field and template is,
// telling a sentence from a word, and turning Anki's scheduling into a FluRai card. Tested in AnkiLogicTest.

// -- field text -------------------------------------------------------------------------------------

// These run on Android's ICU regex engine, which rejects an unescaped "]" or "}" that the JVM accepts:
// escape every literal one (AnkiLogicTest.regexesEscapeClosingBrackets checks).

private val SOUND = Regex("\\[sound:[^\\]]*\\]")
private val BREAK = Regex("(?i)<br\\s*/?>|</div>|</p>|</li>")
private val TAG = Regex("<[^>]*>")
private val ENTITY = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);")
private val SPACES = Regex("\\s+")

/** Anki's furigana syntax: "日本[にほん] 語[ご]" (a space before each base after the first). As Anki's own regex. */
private val FURIGANA = Regex(" ?([^ >]+?)\\[(.+?)\\]")

/** A field as plain text: no HTML, sounds or pictures, entities decoded, spaces collapsed. Furigana brackets stay. */
fun cleanField(raw: String): String {
    val text = raw.replace(SOUND, " ").replace(BREAK, " ").replace(TAG, "")
    val decoded = ENTITY.replace(text) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
            e.startsWith("#") -> e.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
            else -> when (e.lowercase()) {
                "nbsp" -> " "; "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"
                else -> m.value
            }
        }
    }
    return decoded.replace('　', ' ').replace(SPACES, " ").trim()
}

/** The text without furigana: "日本[にほん] 語[ご]" → "日本語". */
fun withoutFurigana(text: String): String = FURIGANA.replace(text) { it.groupValues[1] }.replace(" ", "").trim()

/** The reading from furigana: "日本[にほん] 語[ご]" → "にほんご"; null when the text has no furigana. */
fun furiganaReading(text: String): String? =
    if (!FURIGANA.containsMatchIn(text)) null else FURIGANA.replace(text) { it.groupValues[2] }.replace(" ", "").trim()

fun hasJapanese(text: String) = text.any { isKanji(it) || it in '぀'..'ヿ' }
fun isKanaOnly(text: String) = text.isNotEmpty() && text.all { it in '぀'..'ヿ' || it == 'ー' }

private val SENTENCE_END = Regex("[。！？!?]")

/** A note that is a sentence, not a word: long, or ending like a sentence. Sentences never get a Production card. */
fun looksLikeSentence(expression: String): Boolean = expression.length > 12 || SENTENCE_END.containsMatchIn(expression)

// -- what each field and template is ---------------------------------------------------------------

/** Which field (by ord) holds the word, its reading and its meaning; null when the note type has none. */
data class FieldRoles(val expression: Int?, val reading: Int?, val meaning: Int?)

private val EXPRESSION_NAMES = listOf("expression", "vocab", "word", "japanese", "kanji", "front", "term", "headword", "sentence", "日本語", "単語")
private val READING_NAMES = listOf("reading", "kana", "furigana", "hiragana", "yomi", "pronunciation", "読み")
private val MEANING_NAMES = listOf("meaning", "english", "definition", "translation", "gloss", "back", "意味", "英語")

/**
 * Guesses the fields from their names ("Expression", "Vocab-Kana", "Meaning" ...), then from what the
 * sample notes hold: Japanese with kanji for the word, kana for the reading, Latin letters for the meaning.
 */
fun guessFieldRoles(fields: List<AnkiField>, samples: List<AnkiNote>): FieldRoles {
    fun byName(names: List<String>, taken: Set<Int>) = names.firstNotNullOfOrNull { n ->
        fields.firstOrNull { it.ord !in taken && n in it.name.lowercase() && "audio" !in it.name.lowercase() && "sound" !in it.name.lowercase() }?.ord
    }
    fun texts(ord: Int) = samples.mapNotNull { it.fields.getOrNull(ord)?.let(::cleanField) }.filter { it.isNotEmpty() }
    fun byContent(test: (String) -> Boolean, taken: Set<Int>) =
        fields.firstOrNull { f -> f.ord !in taken && texts(f.ord).let { t -> t.isNotEmpty() && t.count(test) * 2 > t.size } }?.ord

    // Reading first: "Vocab-Kana" is a reading even though it also says "vocab".
    var reading = byName(READING_NAMES, emptySet())
    var expression = byName(EXPRESSION_NAMES, setOfNotNull(reading))
    var meaning = byName(MEANING_NAMES, setOfNotNull(reading, expression))
    if (expression == null) expression = byContent({ hasJapanese(it) && !isKanaOnly(withoutFurigana(it)) }, setOfNotNull(reading, meaning))
        ?: byContent(::hasJapanese, setOfNotNull(reading, meaning))
    if (reading == null) reading = byContent({ isKanaOnly(it) }, setOfNotNull(expression, meaning))
    if (meaning == null) meaning = byContent({ !hasJapanese(it) && it.any(Char::isLetter) }, setOfNotNull(expression, reading))
    return FieldRoles(expression, reading, meaning)
}

/** What a template becomes in FluRai; [Ignore] leaves its cards out (the words still go into the deck). */
enum class TemplateKind(val label: String, val card: CardKind?) {
    Recognition("Recognition", CardKind.Recognition),
    Production("Production", CardKind.Production),
    Listening("Listening", CardKind.Listening),
    Ignore("Leave out", null),
}

private val FIELD_REF = Regex("\\{\\{([^\\}]*)\\}\\}")

/** The fields a template format shows ("{{furigana:Reading}}" shows Reading; "{{FrontSide}}" and "{{#X}}" show nothing). */
fun shownFields(format: String): List<String> = FIELD_REF.findAll(format).map { it.groupValues[1].trim() }
    .filter { it.isNotEmpty() && it[0] !in "#^/!" && it != "FrontSide" }
    .map { it.substringAfterLast(':').trim() }
    .toList()

/** Guesses a template from what its front shows: the word → Recognition, the meaning → Production, only sound → Listening. */
fun guessTemplateKind(template: AnkiTemplate, fields: List<AnkiField>, roles: FieldRoles): TemplateKind {
    val front = shownFields(template.front)
    val byName = fields.associate { it.name to it.ord }
    val ords = front.mapNotNull { byName[it] }
    val name = template.name.lowercase()
    val isTts = "{{tts" in template.front
    return when {
        roles.expression != null && roles.expression in ords -> TemplateKind.Recognition
        roles.meaning != null && roles.meaning in ords -> TemplateKind.Production
        isTts || (ords.isNotEmpty() && ords.all { o -> fields.first { it.ord == o }.name.lowercase().let { "audio" in it || "sound" in it } }) -> TemplateKind.Listening
        "recogn" in name -> TemplateKind.Recognition
        "produc" in name || "recall" in name -> TemplateKind.Production
        "listen" in name -> TemplateKind.Listening
        else -> TemplateKind.Ignore
    }
}

// -- the words of a note ---------------------------------------------------------------------------

/** A note's word as FluRai reads it: [expression] without furigana, [reading] from the reading field or the furigana. */
data class NoteWord(val expression: String, val reading: String, val meaning: String)

fun noteWord(note: AnkiNote, roles: FieldRoles): NoteWord? {
    val rawExpression = roles.expression?.let { note.fields.getOrNull(it) }?.let(::cleanField).orEmpty()
    val expression = withoutFurigana(rawExpression)
    if (expression.isEmpty() || !hasJapanese(expression)) return null
    val readingField = roles.reading?.let { note.fields.getOrNull(it) }?.let(::cleanField).orEmpty()
    val reading = when {
        readingField.isNotEmpty() -> furiganaReading(readingField) ?: withoutFurigana(readingField)
        else -> furiganaReading(rawExpression) ?: if (isKanaOnly(expression)) expression else ""
    }
    val meaning = roles.meaning?.let { note.fields.getOrNull(it) }?.let(::cleanField).orEmpty()
    return NoteWord(expression, reading, meaning)
}

/** The id of a word of the learner's own: negative, from its spelling and reading, so a re-import finds it again. */
fun customWordId(expression: String, reading: String): Int {
    val h = "${expression.trim()}|${reading.trim()}".hashCode() and 0x7FFFFFFF
    return -(if (h == 0) 1 else h)
}

// -- scheduling ------------------------------------------------------------------------------------

/** A card's progress as it comes over: its FSRS state and counts. */
data class SeededCard(val srs: SrsCard, val reps: Int, val lapses: Int, val suspended: Boolean)

private val json = Json { ignoreUnknownKeys = true }

/**
 * Turns an Anki card into FluRai progress. A card that was never answered gives null (its word comes
 * up as new). With FSRS on, Anki keeps the memory state (stability, difficulty) on the card, which is
 * taken as it is; otherwise the review history is replayed through FluRai's scheduler; a card with
 * neither keeps Anki's interval as its stability.
 */
fun seedCard(card: AnkiCard, history: List<AnkiReview>, collectionCreatedSecs: Long, scheduler: FsrsScheduler = FsrsScheduler()): SeededCard? {
    val answers = history.filter { it.ease in 1..4 && it.type in 0..3 }.sortedBy { it.time }
    if (card.type == 0 && answers.isEmpty()) return null
    val suspended = card.queue == -1
    val data = runCatching { json.parseToJsonElement(card.data.ifBlank { "{}" }).jsonObject }.getOrNull()
    val stability = data?.get("s")?.jsonPrimitive?.doubleOrNull
    val difficulty = data?.get("d")?.jsonPrimitive?.doubleOrNull
    val lastReviewSecs = data?.get("lrt")?.jsonPrimitive?.longOrNull
    val due = ankiDue(card, collectionCreatedSecs)

    val srs = when {
        stability != null && difficulty != null && card.type == 2 -> SrsCard(
            state = SrsState.Review, step = null, stability = stability, difficulty = difficulty, due = due,
            lastReview = lastReviewSecs?.let { Instant.ofEpochSecond(it) } ?: answers.lastOrNull()?.let { Instant.ofEpochMilli(it.time) }
                ?: due.minus(Duration.ofDays(card.interval.coerceAtLeast(1).toLong())),
        )
        answers.isNotEmpty() -> answers.fold(SrsCard(due = Instant.ofEpochMilli(answers.first().time))) { c, r ->
            scheduler.review(c, Rating.entries.first { it.value == r.ease }, Instant.ofEpochMilli(r.time))
        }
        card.type == 2 -> SrsCard(
            state = SrsState.Review, step = null, stability = card.interval.coerceAtLeast(1).toDouble(), difficulty = 5.0, due = due,
            lastReview = due.minus(Duration.ofDays(card.interval.coerceAtLeast(1).toLong())),
        )
        else -> return null // learning with no history: start it fresh
    }
    return SeededCard(srs, reps = maxOf(card.reps, answers.size, 1), lapses = card.lapses, suspended = suspended)
}

/** When an Anki card is due: a review card's day counts from the collection's creation; a learning card's due is a time in seconds. */
fun ankiDue(card: AnkiCard, collectionCreatedSecs: Long): Instant = when {
    card.due > 1_000_000_000 -> Instant.ofEpochSecond(card.due)
    else -> Instant.ofEpochSecond(collectionCreatedSecs).plus(Duration.ofDays(card.due))
}

/**
 * Spreads cards that are already due (the learner was away from Anki) over the next days, so they do not
 * all come at once: over a week, or two when there are more than 700. Most overdue first. Returns the new
 * due time per index of [dues]; cards not overdue keep theirs.
 */
fun spreadOverdue(dues: List<Instant>, now: Instant): List<Instant> {
    val overdue = dues.withIndex().filter { it.value <= now }.sortedBy { it.value }
    if (overdue.isEmpty()) return dues
    val days = if (overdue.size > 700) 14 else 7
    val result = dues.toMutableList()
    overdue.forEachIndexed { i, (index, _) -> result[index] = now.plus(Duration.ofDays((i.toLong() * days) / overdue.size)) }
    return result
}
