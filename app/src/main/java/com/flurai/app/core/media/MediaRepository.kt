package com.flurai.app.core.media

import com.flurai.app.core.content.WordCache
import com.flurai.app.core.dictionary.WordSource
import com.flurai.app.core.reading.isKanji
import com.flurai.app.core.knowledge.Knowledge
import com.flurai.app.core.knowledge.knowledge
import com.flurai.app.core.model.JlptLevel
import com.flurai.app.core.study.CardKind
import com.flurai.app.core.study.ItemType
import com.flurai.app.core.study.StudyDao
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** What the detail screen shows about a title: its words found in the dictionary and how many of them the learner knows. */
data class MediaAnalysis(
    /** The title's words that matched a word of ours, in story order, for making a deck ([pickTitleWords]). */
    val words: List<TitleWord>,
    /** How many words the title has in all, counting repeats and words we have no entry for. */
    val totalOccurrences: Int,
    val progress: ShowProgress,
) {
    /** Our ids of every matched word, most common first. */
    val wordIds: List<Int> get() = words.sortedByDescending { it.occurrences }.map { it.id }
}

/**
 * Titles and their words from jiten.moe. Results are kept in memory for a while so moving
 * between screens does not repeat requests; nothing is stored on disk or relayed.
 */
@Singleton
class MediaRepository @Inject constructor(
    private val api: JitenApi,
    private val studyDao: StudyDao,
    private val wordCache: WordCache,
    private val wordSource: WordSource,
) {
    private data class Cached<T>(val at: Long, val value: T)

    private val pages = HashMap<String, Cached<DeckPage>>()
    private val allVocab = HashMap<Int, Cached<List<DeckWord>>>()
    private val decksById = HashMap<Int, Deck>()

    suspend fun page(type: MediaType, level: JlptLevel, offset: Int): DeckPage {
        val key = "${type.id}/${level.name}/$offset"
        fresh(pages[key])?.let { return it }
        val range = difficultyRange(level)
        val result = api.decks(type, range.start, range.endInclusive, offset)
        result.data.forEach { decksById[it.deckId] = it }
        pages[key] = Cached(now(), result)
        return result
    }

    /** A title seen in a list; null after the app restarts (the detail screen then asks to go back). */
    fun deck(id: Int): Deck? = decksById[id]

    /**
     * Reads the title's whole vocabulary from jiten.moe, a page at a time (a few seconds), matches each
     * word (a JMdict entry) to ours (the built-in word when there is one, else the dictionary entry) and
     * counts how many of them the learner already knows.
     */
    suspend fun analyse(uid: String, deckId: Int): MediaAnalysis {
        val vocabulary = fresh(allVocab[deckId])
            ?: allDeckWords { offset -> api.vocabularyPage(deckId, offset) }.also { allVocab[deckId] = Cached(now(), it) }
        val builtIn = wordCache.byJmdictId()
        val ids = vocabulary.map { builtIn[it.wordId] ?: it.wordId }
        val found = wordSource.words(ids.distinct()).associateBy { it.id }
        val known = studyDao.allCards(uid).first()
            .filter { it.itemType == ItemType.Word && it.kind == CardKind.Recognition }
            .filter { it.knowledge().let { k -> k == Knowledge.Known || k == Knowledge.Mature } }
            .map { it.itemId }.toSet()
        val words = vocabulary.zip(ids).mapNotNull { (w, id) ->
            val word = found[id] ?: return@mapNotNull null
            TitleWord(id, w.occurrences, word.jlpt, kanaOnly = word.expression.none(::isKanji), known = id in known)
        }.distinctBy { it.id }
        return MediaAnalysis(words, vocabulary.sumOf { it.occurrences }, showProgress(words.map { it.id }, known))
    }

    private fun <T> fresh(entry: Cached<T>?): T? = entry?.takeIf { now() - it.at < TTL_MS }?.value

    private fun now() = System.currentTimeMillis()

    private companion object {
        const val TTL_MS = 15 * 60 * 1000L
    }
}
