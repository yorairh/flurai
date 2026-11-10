package com.flurai.app.core.quiz

import com.flurai.app.core.content.KanjiEntity
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

/** The first gloss of a JMdict meaning list, e.g. "heaven, the sky" gives "heaven". */
fun firstGloss(meanings: String): String =
    meanings.split(',', ';').first().trim().take(MAX_OPTION_LENGTH)

private const val MAX_OPTION_LENGTH = 40

/** Part-of-speech family used to keep distractors plausible (a noun among nouns, a verb among verbs). */
private fun family(word: WordEntity): String = word.partsOfSpeech?.split(',')?.firstOrNull()?.take(1).orEmpty()

/** "What does this word mean?" with three wrong glosses from [pool]. Null when the pool has too few distinct glosses. */
fun wordQuestion(word: WordEntity, pool: List<WordEntity>, random: Random): Question? {
    val correct = firstGloss(word.meanings)
    if (correct.isBlank()) return null
    val taken = mutableSetOf(correct.lowercase())
    val candidates = pool.asSequence().filter { it.id != word.id }.toList().shuffled(random)
    // Prefer the same word family, then anything.
    val ordered = candidates.filter { family(it) == family(word) } + candidates.filter { family(it) != family(word) }
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

/** One reading to ask about: the first kun'yomi if the kanji has one (without okurigana dots), else the first on'yomi. */
fun mainReading(kanji: KanjiEntity): String? {
    fun clean(s: String) = s.split('、').firstOrNull { it.isNotBlank() }?.replace(".", "")?.replace("-", "")?.trim()
    return clean(kanji.kunyomi) ?: clean(kanji.onyomi)
}

private fun isKatakana(s: String) = s.isNotEmpty() && s.all { it in '゠'..'ヿ' }

/** "How is this kanji read?" with three readings borrowed from other kanji (same script, so the answer isn't obvious). */
fun kanjiQuestion(kanji: KanjiEntity, pool: List<KanjiEntity>, random: Random): Question? {
    val correct = mainReading(kanji) ?: return null
    val taken = mutableSetOf(correct)
    val wrong = mutableListOf<String>()
    for (other in pool.shuffled(random)) {
        if (other.literal == kanji.literal) continue
        val reading = mainReading(other) ?: continue
        if (isKatakana(reading) == isKatakana(correct) && taken.add(reading)) wrong += reading
        if (wrong.size == 3) break
    }
    if (wrong.size < 3) return null
    val options = (wrong + correct).shuffled(random)
    return Question(
        kind = QuestionKind.KanjiReading,
        prompt = kanji.literal,
        hint = "Pick a reading",
        options = options,
        answer = options.indexOf(correct),
        level = kanji.jlpt,
    )
}

/**
 * Adaptive placement: five vocabulary questions per level, starting at N4. Four right passes a
 * level and moves up to the next harder one; fewer moves down. It stops when the next step would
 * revisit a level that was already tested, so the result is the hardest level passed.
 */
class PlacementTest(private val perStage: Int = 5, private val passAt: Int = 4, start: Int = 4) {
    private val scores = mutableMapOf<Int, Int>()
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
        scores[level] = correct
        val next = if (correct >= passAt) level - 1 else level + 1
        asked = 0
        correct = 0
        if (next !in 1..5 || next in scores) finished = true else level = next
    }

    /** Hardest level passed, or Beginner when even N5 was too hard. */
    val result: JlptLevel
        get() = scores.filterValues { it >= passAt }.keys.minOrNull()?.let { JlptLevel.ofJlpt(it) } ?: JlptLevel.Beginner
}

/** How a finished checkpoint or placement counts toward the level estimate. */
const val CHECKPOINT_PASS_FRACTION = 0.8
