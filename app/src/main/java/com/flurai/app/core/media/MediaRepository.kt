package com.flurai.app.core.media

import com.flurai.app.core.content.WordCache
import com.flurai.app.core.knowledge.Knowledge
import com.flurai.app.core.knowledge.knowledge
import com.flurai.app.core.model.JlptLevel
import com.flurai.app.core.study.CardKind
import com.flurai.app.core.study.ItemType
import com.flurai.app.core.study.StudyDao
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** What the detail screen shows about a title: its words in the app's lists and how many of them the learner knows. */
data class MediaAnalysis(
    /** Our ids of every word of the title that the app teaches, most common first. */
    val wordIds: List<Int>,
    val progress: ShowProgress,
)

/**
 * Titles and their words from jiten.moe. Results are kept in memory for a while so moving
 * between screens does not repeat requests; nothing is stored on disk or relayed.
 */
@Singleton
class MediaRepository @Inject constructor(
    private val api: JitenApi,
    private val studyDao: StudyDao,
    private val wordCache: WordCache,
) {
    private data class Cached<T>(val at: Long, val value: T)

    private val pages = HashMap<String, Cached<DeckPage>>()
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

    private suspend fun idMap(): Map<Int, Int> = jmdictToWord ?: wordCache.all()
        .mapNotNull { w -> w.jmdictId?.let { it to w.id } }.toMap().also { jmdictToWord = it }

    /**
     * Reads the title's whole vocabulary from jiten.moe, a page at a time (a few seconds), and
     * counts how many of the words the app teaches the learner already knows.
     */
    suspend fun analyse(uid: String, deckId: Int): MediaAnalysis {
        val words = fresh(allVocab[deckId])
            ?: allDeckWords { offset -> api.vocabularyPage(deckId, offset) }.also { allVocab[deckId] = Cached(now(), it) }
        val wordIds = listedWordIds(words, idMap())
        val known = studyDao.allCards(uid).first()
            .filter { it.itemType == ItemType.Word && it.kind == CardKind.Recognition }
            .filter { it.knowledge().let { k -> k == Knowledge.Known || k == Knowledge.Mature } }
            .map { it.itemId }.toSet()
        return MediaAnalysis(wordIds, showProgress(wordIds, known))
    }

    private fun <T> fresh(entry: Cached<T>?): T? = entry?.takeIf { now() - it.at < TTL_MS }?.value

    private fun now() = System.currentTimeMillis()

    private companion object {
        const val TTL_MS = 15 * 60 * 1000L
    }
}
