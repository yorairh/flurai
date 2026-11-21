package com.flurai.app.core.dictionary

import com.flurai.app.core.content.WordCache
import com.flurai.app.core.study.WordMergeDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A content update can add a word to the built-in lists that a learner already studies as a
 * dictionary word (its JMdict id). Then the two ids are the same word: [merge] moves every card,
 * review and deck entry to the built-in id, so the word counts toward its level and is not listed twice.
 *
 * Run on start (after a content update). Sync applies the same mapping to what it pulls
 * ([SyncEngine] is given [WordSource.resolve]), so an older backup or another phone cannot bring the
 * dictionary id back; the old cloud documents are left alone and simply map onto the built-in word.
 */
@Singleton
class WordMerger @Inject constructor(
    private val study: WordMergeDao,
    private val cache: WordCache,
) {
    /** Merges every dictionary id that has become a built-in word; returns how many ids were merged. */
    suspend fun merge(): Int {
        val builtIn = cache.byJmdictId()
        val ids = (study.cardWordIdsFrom(DICTIONARY_ID_START) + study.deckWordIdsFrom(DICTIONARY_ID_START)).toSet()
        val moves = mergeTargets(ids, builtIn)
        moves.forEach { (from, to) -> study.mergeWord(from, to) }
        return moves.size
    }
}

/** The (dictionary id, built-in id) pairs to merge among [ids], given JMdict id -> built-in id. */
fun mergeTargets(ids: Collection<Int>, builtInByJmdict: Map<Int, Int>): List<Pair<Int, Int>> =
    ids.filter(::isDictionaryId).mapNotNull { id -> builtInByJmdict[id]?.let { id to it } }.sortedBy { it.first }
