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
        // Lower JLPT number = harder, so go hardest first and let the easier (more common) word win. Between
        // words of one level the lower id (the first in the list, e.g. わたし before わたくし) is processed last.
        for (word in words.sortedWith(compareBy<WordEntity> { it.jlpt }.thenByDescending { it.id })) {
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

/** A short set of sentences to read, all at the same level. */
data class ReadingSet(val jlpt: Int, val sentences: List<SentenceEntity>)

/**
 * "What does this sentence mean?" with the translations of other sentences as wrong options. Wrong
 * options come first from sentences that share a word with this one ([keywords]), so knowing one word
 * is not enough to pick the answer. Null when there are not enough different translations.
 */
fun sentenceQuestion(
    sentence: SentenceEntity,
    pool: List<SentenceEntity>,
    jlpt: Int,
    random: Random,
    keywords: List<String> = emptyList(),
): Question? {
    val correct = sentence.english.trim()
    val others = pool.asSequence().filter { it.id != sentence.id }
        .filter { it.english.isNotBlank() && !it.english.trim().equals(correct, ignoreCase = true) }
        .distinctBy { it.english.trim().lowercase() }
        .toList().shuffled(random)
    val (related, unrelated) = others.partition { other -> keywords.any { it in other.japanese } }
    // Similar length so the answer cannot be guessed by how long it is.
    fun byLength(list: List<SentenceEntity>) = list.sortedBy { kotlin.math.abs(it.english.trim().length - correct.length) }
    val wrong = (byLength(related.take(RELATED_POOL)) + byLength(unrelated)).map { it.english.trim() }.distinct().take(3)
    if (wrong.size < 3) return null
    val options = (wrong + correct).shuffled(random)
    return Question(
        kind = QuestionKind.SentenceMeaning,
        prompt = sentence.japanese,
        hint = null,
        options = options,
        answer = options.indexOf(correct),
        level = jlpt,
    )
}

/** How many sentences that share a word are considered before falling back to unrelated ones. */
private const val RELATED_POOL = 8

@Singleton
class ReadingRepository @Inject constructor(private val content: ContentDao, private val wordCache: WordCache) {
    private var lexicon: Lexicon? = null

    suspend fun lexicon(): Lexicon = lexicon ?: wordCache.all().let { words -> withContext(Dispatchers.Default) { Lexicon(words) } }.also { lexicon = it }

    private var byLevel: Map<Int, List<SentenceEntity>>? = null

    /** Sentences grouped by level. The content pipeline grades them (hardest word or grammar), so this is a plain query. */
    private suspend fun pool(): Map<Int, List<SentenceEntity>> =
        byLevel ?: content.readingSentences().groupBy { it.jlpt ?: 0 }.filterKeys { it in 1..5 }.also { byLevel = it }

    /** A fresh random set of [count] verified sentences whose hardest word is at [jlpt]. */
    suspend fun set(jlpt: Int, count: Int = SET_SIZE, random: Random = Random): ReadingSet =
        ReadingSet(jlpt, pool()[jlpt].orEmpty().shuffled(random).take(count))

    /** Questions for the comprehension check. */
    suspend fun questions(jlpt: Int, count: Int = 10, random: Random = Random): List<Question> {
        val pool = pool()[jlpt].orEmpty()
        val lexicon = lexicon()
        return pool.shuffled(random).asSequence().mapNotNull { sentence ->
            // Longer words are better clues than one-character ones.
            val keywords = lexicon.tokenize(sentence.japanese).mapNotNull { token -> token.text.takeIf { token.word != null && it.length >= 2 } }
            sentenceQuestion(sentence, pool, jlpt, random, keywords)
        }.take(count).toList()
    }

    companion object {
        const val SET_SIZE = 6
    }
}
