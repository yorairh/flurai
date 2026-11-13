package com.flurai.app.core.media

import com.flurai.app.core.content.WordCache
import com.flurai.app.core.content.ContentDao
import com.flurai.app.core.content.WordEntity
import com.flurai.app.core.knowledge.Knowledge
import com.flurai.app.core.knowledge.knowledge
import com.flurai.app.core.model.JlptLevel
import com.flurai.app.core.study.CardKind
import com.flurai.app.core.study.ItemRef
import com.flurai.app.core.study.ItemType
import com.flurai.app.core.study.StudyDao
import com.flurai.app.core.study.StudyRepository
import kotlinx.coroutines.flow.first
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** What the detail screen shows about a title next to the learner's own progress. */
data class MediaAnalysis(
    val coverage: MediaCoverage,
    /** The title's common words that can be added to the flashcards, most common first. */
    val learnable: List<WordEntity>,
    /** Common words left out because they are well above the learner's level; "include harder words" brings them back. */
    val skippedHarder: Int = 0,
)

/**
 * Titles and word coverage from jiten.moe. Results are kept in memory for a while so moving
 * between screens does not repeat requests; nothing is stored on disk or relayed.
 */
@Singleton
class MediaRepository @Inject constructor(
    private val api: JitenApi,
    private val content: ContentDao,
    private val studyDao: StudyDao,
    private val study: StudyRepository,
    private val wordCache: WordCache,
) {
    private data class Cached<T>(val at: Long, val value: T)

    private val pages = HashMap<String, Cached<DeckPage>>()
    private val vocab = HashMap<Int, Cached<List<DeckWord>>>()
    private val allVocab = HashMap<Int, Cached<List<DeckWord>>>()
    private val decksById = HashMap<Int, Deck>()
    private var jmdictToWord: Map<Int, Int>? = null

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

    private suspend fun topWords(deckId: Int): List<DeckWord> {
        fresh(vocab[deckId])?.let { return it }
        return api.topVocabulary(deckId).also { vocab[deckId] = Cached(now(), it) }
    }

    private suspend fun idMap(): Map<Int, Int> = jmdictToWord ?: wordCache.all()
        .mapNotNull { w -> w.jmdictId?.let { it to w.id } }.toMap().also { jmdictToWord = it }

    suspend fun analyse(
        uid: String,
        deckId: Int,
        level: JlptLevel,
        includeHarder: Boolean = false,
        learnLimit: Int = LEARN_BATCH,
    ): MediaAnalysis {
        val words = topWords(deckId)
        val map = idMap()
        val cards = studyDao.allCards(uid).first().filter { it.itemType == ItemType.Word }
        val knownWordIds = cards.filter { it.kind == CardKind.Recognition && it.knowledge().let { k -> k == Knowledge.Known || k == Knowledge.Mature } }
            .map { it.itemId }.toSet()
        val knownJmdict = map.filterValues { it in knownWordIds }.keys
        val started = cards.map { it.itemId }.toSet()
        val byId = wordCache.all().associateBy { it.id }
        val levelOf = { id: Int -> byId[id]?.jlpt }
        val offered = wordsToLearn(words, map, started, learnLimit, levelOf, if (includeHarder) 1 else hardestWordToOffer(level))
        val skipped = if (includeHarder) 0 else wordsToLearn(words, map, started, learnLimit).count { it !in offered }
        return MediaAnalysis(coverage(words, knownJmdict), offered.mapNotNull { byId[it] }, skipped)
    }

    /**
     * Our ids of every word of the title that is in the app's word lists, most common first. Asks
     * jiten.moe for the whole vocabulary, a page at a time, so it takes a few seconds.
     */
    suspend fun allWordIds(deckId: Int): List<Int> {
        val words = fresh(allVocab[deckId])
            ?: allDeckWords { offset -> api.vocabularyPage(deckId, offset) }.also { allVocab[deckId] = Cached(now(), it) }
        return allListedWords(words, idMap())
    }

    /** Adds the given words to the flashcards as new cards for today. */
    suspend fun addToFlashcards(uid: String, wordIds: List<Int>) {
        val now = Instant.now()
        wordIds.forEach { study.addToStudy(uid, ItemRef(ItemType.Word, it), now) }
    }

    private fun <T> fresh(entry: Cached<T>?): T? = entry?.takeIf { now() - it.at < TTL_MS }?.value

    private fun now() = System.currentTimeMillis()

    companion object {
        const val LEARN_BATCH = 20
        private const val TTL_MS = 15 * 60 * 1000L
    }
}
