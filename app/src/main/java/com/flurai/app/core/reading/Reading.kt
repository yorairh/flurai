package com.flurai.app.core.reading

import com.flurai.app.core.content.WordCache
import com.flurai.app.core.content.ContentDao
import com.flurai.app.core.content.SentenceEntity
import com.flurai.app.core.content.WordEntity
import com.flurai.app.core.quiz.Question
import com.flurai.app.core.quiz.QuestionKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/** A piece of a sentence; [word] is set when it is a dictionary word the learner can tap. */
data class Token(val text: String, val word: WordEntity?)

/**
 * Looks words up by how they are written (the main spelling and other forms). Matching is
 * longest-first from each position, with no conjugation handling: 食べた is found through 食べ only
 * when the list holds that exact form, so inflected endings stay as plain text.
 */
class Lexicon(words: List<WordEntity>) {
    private val bySpelling: Map<String, WordEntity>
    private val longest: Int

    init {
        val map = HashMap<String, WordEntity>()
        // Lower JLPT number = harder, so go hardest first and let the easier (more common) word win.
        for (word in words.sortedBy { it.jlpt }) {
            for (form in spellings(word)) map[form] = word
        }
        bySpelling = map
        longest = map.keys.maxOfOrNull { it.length } ?: 0
    }

    private fun spellings(word: WordEntity): List<String> =
        (listOf(word.expression) + word.otherForms.orEmpty().split("; ")).map { it.trim() }.filter { isLookupWorthy(it) }

    /** Splits [text] into tokens; unmatched characters are merged into plain runs. */
    fun tokenize(text: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val plain = StringBuilder()
        fun flush() {
            if (plain.isNotEmpty()) tokens += Token(plain.toString(), null).also { plain.clear() }
        }
        var i = 0
        while (i < text.length) {
            var matched: WordEntity? = null
            var length = minOf(longest, text.length - i)
            while (length >= 1) {
                val candidate = text.substring(i, i + length)
                matched = bySpelling[candidate]
                if (matched != null) break
                length--
            }
            if (matched != null) {
                flush()
                tokens += Token(text.substring(i, i + length), matched)
                i += length
            } else {
                plain.append(text[i])
                i++
            }
        }
        flush()
        return tokens
    }
}

/** One character kanji words are fine; a lone kana is a particle or an ending, so it is not tappable. */
private fun isLookupWorthy(form: String): Boolean =
    form.length >= 2 || form.any { it in '一'..'鿿' }

/**
 * The JLPT level of a sentence: its hardest word. Null when it cannot be judged, which is when no
 * word is recognised or a kanji word is missing from the word lists (so it may be far harder).
 */
fun sentenceLevel(tokens: List<Token>): Int? {
    if (tokens.any { it.word == null && it.text.any { c -> c in '一'..'鿿' } }) return null
    return tokens.mapNotNull { it.word?.jlpt }.minOrNull()
}

/** A short set of sentences to read, all at the same level. */
data class ReadingSet(val jlpt: Int, val sentences: List<SentenceEntity>)

/**
 * "What does this sentence mean?" with the translations of other sentences as wrong options.
 * Null when there are not enough different translations.
 */
fun sentenceQuestion(sentence: SentenceEntity, pool: List<SentenceEntity>, jlpt: Int, random: Random): Question? {
    val wrong = pool.asSequence().filter { it.id != sentence.id }.map { it.english.trim() }
        .filter { it.isNotBlank() && !it.equals(sentence.english.trim(), ignoreCase = true) }
        .distinct().toList().shuffled(random)
        // Similar length so the answer cannot be guessed by how long it is.
        .sortedBy { kotlin.math.abs(it.length - sentence.english.length) }
        .take(3)
    if (wrong.size < 3) return null
    val options = (wrong + sentence.english.trim()).shuffled(random)
    return Question(
        kind = QuestionKind.SentenceMeaning,
        prompt = sentence.japanese,
        hint = null,
        options = options,
        answer = options.indexOf(sentence.english.trim()),
        level = jlpt,
    )
}

@Singleton
class ReadingRepository @Inject constructor(private val content: ContentDao, private val wordCache: WordCache) {
    private var lexicon: Lexicon? = null

    suspend fun lexicon(): Lexicon = lexicon ?: wordCache.all().let { words -> withContext(Dispatchers.Default) { Lexicon(words) } }.also { lexicon = it }

    private var byLevel: Map<Int, List<SentenceEntity>>? = null

    /** Sentences grouped by level, worked out once from the words they contain. */
    private suspend fun pool(): Map<Int, List<SentenceEntity>> = byLevel ?: withContext(Dispatchers.Default) {
        val lex = lexicon()
        content.readingCandidates().groupBy { sentenceLevel(lex.tokenize(it.japanese)) }
            .filterKeys { it != null }.mapKeys { it.key!! }
    }.also { byLevel = it }

    /** A fresh random set of [count] verified sentences whose hardest word is at [jlpt]. */
    suspend fun set(jlpt: Int, count: Int = SET_SIZE, random: Random = Random): ReadingSet =
        ReadingSet(jlpt, pool()[jlpt].orEmpty().shuffled(random).take(count))

    /** Questions for the comprehension check. */
    suspend fun questions(jlpt: Int, count: Int = 10, random: Random = Random): List<Question> {
        val pool = pool()[jlpt].orEmpty()
        return pool.shuffled(random).asSequence().mapNotNull { sentenceQuestion(it, pool, jlpt, random) }.take(count).toList()
    }

    companion object {
        const val SET_SIZE = 6
    }
}
