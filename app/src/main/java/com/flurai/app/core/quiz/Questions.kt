package com.flurai.app.core.quiz

import com.flurai.app.core.content.WordEntity
import com.flurai.app.core.model.JlptLevel
import kotlin.random.Random

enum class QuestionKind { WordMeaning, KanjiReading, GrammarBlank, SentenceMeaning, WordListening }

/**
 * A multiple-choice question. [wordId] is set for vocabulary questions so a missed word can be
 * added to the flashcards; [level] is the JLPT number the item belongs to.
 */
data class Question(
    val kind: QuestionKind,
    val prompt: String,
    /** Reading under a word, or the instruction under a kanji. */
    val hint: String?,
    val options: List<String>,
    val answer: Int,
    val level: Int,
    val wordId: Int? = null,
    /** Set for grammar questions so the answer can be saved against the point. */
    val grammarId: Int? = null,
    /** Shown once the question is answered, e.g. which grammar point it tested. */
    val note: String? = null,
) {
    val correctOption: String get() = options[answer]
}

/**
 * The first gloss of a JMdict meaning list, e.g. "heaven, the sky" gives "heaven". Commas inside
 * brackets do not split ("cold (things, drinks), chilly" gives "cold (things, drinks)"), and a gloss
 * that is too long is cut at a word boundary, never in the middle of a word or a bracket.
 */
fun firstGloss(meanings: String): String {
    val first = splitGlosses(meanings).firstOrNull().orEmpty()
    if (first.length <= MAX_OPTION_LENGTH) return first
    val cut = first.take(MAX_OPTION_LENGTH)
    val atWord = if (first[MAX_OPTION_LENGTH] == ' ') cut else cut.substringBeforeLast(' ', cut)
    // A bracket that the cut left open is dropped with what is in it.
    return atWord.replace(Regex("\\s*\\([^)]*$"), "").trim().trimEnd(',', ';')
}

/** The glosses of [meanings], split at commas and semicolons that are not inside brackets. */
fun splitGlosses(meanings: String): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var depth = 0
    for (c in meanings) {
        when {
            c == '(' -> { depth++; current.append(c) }
            c == ')' -> { depth = (depth - 1).coerceAtLeast(0); current.append(c) }
            (c == ',' || c == ';') && depth == 0 -> { parts += current.toString().trim(); current.clear() }
            else -> current.append(c)
        }
    }
    parts += current.toString().trim()
    return parts.filter { it.isNotEmpty() }
}

private const val MAX_OPTION_LENGTH = 40

/** What kind of word it is, from the first JMdict part-of-speech code: a noun among nouns, a verb among verbs, an い-adjective among い-adjectives. */
internal fun family(word: WordEntity): String {
    val code = word.partsOfSpeech?.split(',')?.firstOrNull()?.trim().orEmpty()
    return when {
        code.startsWith("v") -> "verb"
        code == "adj-i" || code == "adj-ix" -> "i-adjective"
        code == "adj-na" -> "na-adjective"
        code.startsWith("adv") -> "adverb"
        code.startsWith("n") -> "noun"
        else -> code
    }
}

/** "What does this word mean?" with three wrong glosses from [pool]. Null when the pool has too few distinct glosses. */
fun wordQuestion(word: WordEntity, pool: List<WordEntity>, random: Random): Question? {
    val correct = firstGloss(word.meanings)
    if (correct.isBlank()) return null
    val taken = mutableSetOf(correct.lowercase())
    val candidates = pool.asSequence().filter { it.id != word.id }.toList().shuffled(random)
    // Wrong options that look like the answer: the same kind of word at the same level first, then the
    // same kind of word, and only then anything. A noun among verbs would give the answer away.
    val sameFamily = candidates.filter { family(it) == family(word) }
    val ordered = sameFamily.filter { it.jlpt == word.jlpt } + sameFamily.filter { it.jlpt != word.jlpt } + candidates.filter { family(it) != family(word) }
    val wrong = mutableListOf<String>()
    for (candidate in ordered) {
        val gloss = firstGloss(candidate.meanings)
        if (gloss.isNotBlank() && taken.add(gloss.lowercase())) wrong += gloss
        if (wrong.size == 3) break
    }
    if (wrong.size < 3) return null
    val options = (wrong + correct).shuffled(random)
    return Question(
        kind = QuestionKind.WordMeaning,
        prompt = word.expression,
        hint = word.reading.takeIf { it != word.expression },
        options = options,
        answer = options.indexOf(correct),
        level = word.jlpt,
        wordId = word.id,
    )
}

/**
 * "What did you hear?": the word is spoken (the prompt holds its reading, which is what gets read
 * aloud) and the options are meanings. The written word is only shown after answering.
 */
fun listeningQuestion(word: WordEntity, pool: List<WordEntity>, random: Random): Question? =
    wordQuestion(word, pool, random)?.copy(
        kind = QuestionKind.WordListening,
        prompt = word.reading,
        hint = null,
        note = if (word.reading != word.expression) "${word.expression}（${word.reading}）" else word.expression,
    )

/** Katakana-only text (a loanword) is read from its spelling, so it makes no reading question. */
private fun hasKanji(text: String) = text.any { it in '一'..'鿿' || it == '々' }

/**
 * "How is this word read?" for a word written with kanji. The wrong options are the readings of other
 * kanji words of about the same length, so the answer cannot be guessed by its shape. Asking about a
 * whole word rather than one kanji keeps okurigana and the common reading in the question.
 */
fun wordReadingQuestion(word: WordEntity, pool: List<WordEntity>, random: Random): Question? {
    if (!hasKanji(word.expression) || word.reading == word.expression) return null
    val correct = word.reading
    val taken = mutableSetOf(correct)
    val similar = pool.asSequence().filter { it.id != word.id && hasKanji(it.expression) && it.reading != it.expression }
        .toList().shuffled(random)
        .sortedBy { kotlin.math.abs(it.reading.length - correct.length) }
    val wrong = mutableListOf<String>()
    for (other in similar) {
        if (taken.add(other.reading)) wrong += other.reading
        if (wrong.size == 3) break
    }
    if (wrong.size < 3) return null
    val options = (wrong + correct).shuffled(random)
    return Question(
        kind = QuestionKind.KanjiReading,
        prompt = word.expression,
        hint = "How is this read?",
        options = options,
        answer = options.indexOf(correct),
        level = word.jlpt,
        wordId = word.id,
        note = firstGloss(word.meanings),
    )
}

/**
 * Adaptive placement: from N5 upward, [perStage] questions per level; [passAt] right passes the
 * level and moves on to the next harder one, anything less stops the test. The result is the
 * hardest level passed, or Beginner when even N5 was too hard. It only suggests where to start:
 * a level counts for the learner's estimate once its checkpoint is passed or its words and
 * grammar are known.
 */
class PlacementTest(private val perStage: Int = 6, private val passAt: Int = 5, start: Int = 5) {
    private val passed = mutableSetOf<Int>()
    private var asked = 0
    private var correct = 0

    /** JLPT number being asked about now. */
    var level: Int = start
        private set
    var finished: Boolean = false
        private set

    fun answer(isCorrect: Boolean) {
        check(!finished) { "placement test already finished" }
        asked++
        if (isCorrect) correct++
        if (asked < perStage) return
        val passedStage = correct >= passAt
        if (passedStage) passed += level
        asked = 0
        correct = 0
        if (passedStage && level > 1) level-- else finished = true
    }

    /** Hardest level passed, or Beginner when even N5 was too hard. */
    val result: JlptLevel
        get() = passed.minOrNull()?.let { JlptLevel.ofJlpt(it) } ?: JlptLevel.Beginner
}

/** How a finished checkpoint or placement counts toward the level estimate. */
const val CHECKPOINT_PASS_FRACTION = 0.8
