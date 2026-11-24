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

private fun isJapaneseChar(c: Char) = isKanji(c) || c in '぀'..'ヿ'
fun hasJapanese(text: String) = text.any(::isJapaneseChar)
fun isKanaOnly(text: String) = text.isNotEmpty() && text.all { it in '぀'..'ヿ' || it == 'ー' }

private val SENTENCE_END = Regex("[。！？!?]")

/** A note that is a sentence, not a word: long, or ending like a sentence. Sentences never get a Production card. */
fun looksLikeSentence(expression: String): Boolean = expression.length > 12 || SENTENCE_END.containsMatchIn(expression)

// -- what each field and template is ---------------------------------------------------------------

/** Which field (by ord) holds the word, its reading and its meaning; null when the note type has none. */
data class FieldRoles(val expression: Int?, val reading: Int?, val meaning: Int?)

private val EXPRESSION_NAMES = listOf("expression", "vocab", "word", "japanese", "kanji", "front", "term", "headword", "日本語", "単語", "单词", "日文", "日语", "sentence")
private val READING_NAMES = listOf("reading", "kana", "furigana", "hiragana", "yomi", "pronunciation", "読み", "假名", "仮名", "かな", "ふりがな")
private val MEANING_NAMES = listOf("meaning", "english", "definition", "def", "translation", "gloss", "eng", "back", "意味", "英語", "释义", "中文", "意思")

/** Fields that never hold the word, its reading or its meaning: media, ids, sort keys, part of speech ... */
private val NOT_TEXT = Regex("radical|component|audio|sound|image|picture|pitch|stroke|diagram|uri$|url$|index|sort|order|freq|tags?$|id$|pos$|type$|romaji|notes?$|grammar")
/** Fields of a deck's example sentence ("Sentence-Kana", "SentFurigana1", "Example EN"). */
private val SENTENCE_FIELD = Regex("sentence|sent|example|cloze|例")
/** A field that is a second choice for its role: "Additional definitions", "English Definition (Lengthy Version)". */
private val SECONDARY = Regex("additional|extra|other|lengthy|long|alt|more|[2-9]$")

/** A value that is data, not text: JSON or a list of key=value ("[\"基盤\",\"きばん\" ...]"). */
private fun looksLikeData(text: String) = text.count { it in "\"{}[]=;|" } >= 3

private const val PUNCTUATION = " 。！？!?・〜~()（）「」"

/** What is left of a field's name without the role words: "VocabKanji" and "VocabFurigana" are both "vocab". */
private val ROLE_WORDS = Regex("expression|japanese|kanji|kana|reading|furigana|hiragana|yomi|meaning|english|definition|def|eng|translation|gloss|word|with|ruby")

private fun stem(name: String) = name.lowercase().replace("vocabulary", "vocab").replace(ROLE_WORDS, "").filter(Char::isLetterOrDigit)

/**
 * Whether [reading] can be how [expression] is read: kana only, hiragana for a word with kanji (a katakana
 * "reading" next to a kanji is its on'yomi), and keeping the word's own kana (食べる → たべる, not ショク).
 */
private fun fitsAsReading(expression: String, text: String): Boolean {
    val reading = text.filter { it !in PUNCTUATION }
    if (!isKanaOnly(reading)) return false
    if (expression.isEmpty()) return true
    if (expression.any(::isKanji) && reading.count { it in 'ァ'..'ヺ' } * 2 > reading.length) return false
    return expression.filter { it in 'ぁ'..'ゖ' }.all { it in reading }
}

/**
 * Guesses the fields: the word first (a field named for it that holds Japanese, preferring single words over
 * sentences, so Core 2000 takes "Vocabulary-Kanji" over its sentence "Expression"), then the reading and the
 * meaning that go with it (named like it: "VocabKanji" → "VocabFurigana", "VocabDef"), each from what its name
 * says and what the sample notes hold. With no named field, the contents decide.
 */
fun guessFieldRoles(fields: List<AnkiField>, samples: List<AnkiNote>): FieldRoles {
    // Only values with words count: a "1" or "⓪" in a flag or pitch field is as good as empty.
    fun texts(ord: Int) = samples.mapNotNull { it.fields.getOrNull(ord)?.let(::cleanField) }.filter { it.any(Char::isLetter) }
    fun mostly(ord: Int, test: (String) -> Boolean) = texts(ord).let { t -> t.isNotEmpty() && t.count(test) * 2 > t.size }
    fun lower(ord: Int) = fields.first { it.ord == ord }.name.lowercase()
    val text = fields.filter { !NOT_TEXT.containsMatchIn(it.name.lowercase()) }
    fun named(names: List<String>, taken: Set<Int>) =
        names.flatMap { n -> text.filter { it.ord !in taken && n in it.name.lowercase() } }.map { it.ord }.distinct()
    fun nameRank(names: List<String>, ord: Int) = names.indexOfFirst { it in lower(ord) }
    val japanese = { ord: Int -> mostly(ord, ::hasJapanese) }
    val sentences = { ord: Int -> mostly(ord) { looksLikeSentence(withoutFurigana(it)) } }

    // The word: never a field named as a reading or meaning ("Word Reading", "Vocabulary-English").
    val wordCandidates = named(EXPRESSION_NAMES, emptySet())
        .filter { o -> READING_NAMES.none { it in lower(o) } && MEANING_NAMES.none { it in lower(o) } }
        .filter { texts(it).isEmpty() || (japanese(it) && !mostly(it, ::looksLikeData)) }
    var expression = wordCandidates.sortedWith(
        compareBy<Int>(
            { texts(it).isEmpty() }, { SENTENCE_FIELD.containsMatchIn(lower(it)) }, { sentences(it) }, { SECONDARY.containsMatchIn(lower(it)) },
            { nameRank(EXPRESSION_NAMES, it) }, { it },
        ),
    ).firstOrNull()
    val expressionStem = expression?.let { stem(lower(it)) }
    fun expressionTexts(ord: Int) = samples.map { n ->
        withoutFurigana(expression?.let { n.fields.getOrNull(it) }?.let(::cleanField).orEmpty()) to n.fields.getOrNull(ord)?.let(::cleanField).orEmpty()
    }.filter { it.second.isNotEmpty() }
    // A reading must read the word: kana (or the word with furigana) that fits it, in most sample notes.
    fun readsTheWord(ord: Int, orNoSamples: Boolean = true) = expressionTexts(ord).let { pairs ->
        if (pairs.isEmpty()) orNoSamples else pairs.count { (e, r) -> fitsAsReading(e, (furiganaReading(r) ?: r).replace(" ", "")) } * 2 > pairs.size
    }

    var reading = named(READING_NAMES, setOfNotNull(expression)).filter { readsTheWord(it) }.sortedWith(
        compareBy<Int>(
            { texts(it).isEmpty() }, { stem(lower(it)) != expressionStem }, { SENTENCE_FIELD.containsMatchIn(lower(it)) }, { sentences(it) },
            { SECONDARY.containsMatchIn(lower(it)) }, { nameRank(READING_NAMES, it) }, { it },
        ),
    ).firstOrNull()
    var meaning = named(MEANING_NAMES, setOfNotNull(expression, reading)).sortedWith(
        compareBy<Int>(
            { texts(it).isEmpty() }, { stem(lower(it)) != expressionStem }, { SENTENCE_FIELD.containsMatchIn(lower(it)) },
            { "kanji" in lower(it) }, { SECONDARY.containsMatchIn(lower(it)) }, { mostly(it) { t -> t.count(::isJapaneseChar) > t.count { c -> c in 'a'..'z' || c in 'A'..'Z' } } }, { nameRank(MEANING_NAMES, it) }, { it },
        ),
    ).firstOrNull()

    fun byContent(test: (String) -> Boolean, taken: Set<Int>) =
        text.firstOrNull { f -> f.ord !in taken && mostly(f.ord, test) }?.ord
    if (expression == null) expression = byContent({ hasJapanese(it) && !isKanaOnly(withoutFurigana(it)) }, setOfNotNull(reading, meaning))
        ?: byContent(::hasJapanese, setOfNotNull(reading, meaning))
    // A reading found by content alone: not a field named for something else ("vocabKey2" is another word).
    if (reading == null) reading = text.firstOrNull { f ->
        val name = f.name.lowercase()
        f.ord != expression && f.ord != meaning && (EXPRESSION_NAMES + MEANING_NAMES).none { it in name } &&
            !SENTENCE_FIELD.containsMatchIn(name) && readsTheWord(f.ord, orNoSamples = false)
    }?.ord
    if (meaning == null) meaning = byContent({ !hasJapanese(it) && it.any(Char::isLetter) }, setOfNotNull(expression, reading))
    // A deck of kana words: the only Japanese field was taken as the reading, so it is the word.
    if (expression == null && reading != null) { expression = reading; reading = null }
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
    fun field(ord: Int?) = ord?.let { note.fields.getOrNull(it) }?.let(::cleanField).orEmpty().let(::unwrap)
    val readingField = field(roles.reading)
    // A kana word often leaves the kanji field empty ("kanjis" in Genki decks): the word is its reading.
    val rawExpression = field(roles.expression).ifEmpty { readingField }
    val expression = withoutFurigana(rawExpression)
    if (expression.isEmpty() || !hasJapanese(expression)) return null
    val reading = when {
        isKanaOnly(expression) -> expression
        readingField.isNotEmpty() -> (furiganaReading(readingField) ?: withoutFurigana(readingField)).replace(" ", "")
        else -> furiganaReading(rawExpression) ?: ""
    }
    val meaning = roles.meaning?.let { note.fields.getOrNull(it) }?.let(::cleanField).orEmpty()
    return NoteWord(expression, reading, meaning)
}

private val BRACKETED = Regex("[(（]([^)）]*)[)）]")

/**
 * The ways to look a note's word up, best first: as it is, without a bracketed part or as that part
 * ("なる(生る)" → なる, 生る), and a する verb as its noun ("ごちそうする" → ごちそう). Sentences only as they are.
 */
fun matchVariants(word: NoteWord): List<NoteWord> {
    if (looksLikeSentence(word.expression)) return listOf(word)
    val variants = mutableListOf(word)
    // "〜と申します", "~さん": the word without the tilde that stands for what comes before or after it.
    val tilde = charArrayOf('〜', '~', '～')
    val trimmed = word.copy(expression = word.expression.trim(*tilde), reading = word.reading.trim(*tilde))
    if (trimmed != word && trimmed.expression.isNotEmpty()) variants += trimmed
    BRACKETED.find(word.expression)?.let { m ->
        val outside = word.expression.replace(BRACKETED, "").trim()
        val inside = m.groupValues[1].trim()
        val reading = word.reading.replace(BRACKETED, "").trim()
        if (hasJapanese(outside)) variants += word.copy(expression = outside, reading = reading)
        if (hasJapanese(inside)) variants += word.copy(expression = inside, reading = reading)
    }
    variants.toList().forEach { v ->
        if (v.expression.length > 2 && v.expression.endsWith("する") && v.reading.endsWith("する")) {
            variants += v.copy(expression = v.expression.dropLast(2), reading = v.reading.dropLast(2))
        }
    }
    // Textbook decks (Minna no Nihongo) list verbs in their ます form: try the dictionary forms it can come from.
    variants.toList().forEach { v -> variants += dictionaryForms(v) }
    return variants.distinct()
}

private val I_TO_U = mapOf('い' to 'う', 'き' to 'く', 'ぎ' to 'ぐ', 'し' to 'す', 'ち' to 'つ', 'に' to 'ぬ', 'び' to 'ぶ', 'み' to 'む', 'り' to 'る')

/** The dictionary forms a ます form can come from ("向かいます" → 向かう; "食べます" → 食べる; "します" → する). */
private fun dictionaryForms(word: NoteWord): List<NoteWord> {
    if (!word.expression.endsWith("ます") || !word.reading.endsWith("ます") || word.reading.length < 3) return emptyList()
    val e = word.expression.dropLast(2)
    val r = word.reading.dropLast(2)
    val forms = mutableListOf(word.copy(expression = e + "る", reading = r + "る")) // ichidan: 食べ → 食べる
    I_TO_U[r.last()]?.let { u ->
        // Godan: the last kana turns from the i row to the u row (in the spelling too when it is kana).
        val spelling = if (e.last() == r.last()) e.dropLast(1) + u else e
        forms += word.copy(expression = spelling, reading = r.dropLast(1) + u)
    }
    if (r.endsWith("し")) forms += word.copy(expression = e.removeSuffix("し") + "する", reading = r.dropLast(1) + "する")
    if (r.endsWith("き") && (r == "き" || e.endsWith("来"))) forms += word.copy(expression = e.removeSuffix("き") + "る", reading = r.dropLast(1) + "くる")
    return forms
}

/** A whole field in brackets, as some decks write furigana ("[肩[かた]]"), without them. */
private fun unwrap(text: String) = if (text.length > 2 && text.first() == '[' && text.last() == ']' && '[' in text.substring(1)) text.substring(1, text.length - 1) else text

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
