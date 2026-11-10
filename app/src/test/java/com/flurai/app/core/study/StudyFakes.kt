package com.flurai.app.core.study

import com.flurai.app.core.content.ContentDao
import com.flurai.app.core.content.ContentInfoEntity
import com.flurai.app.core.content.KanjiEntity
import com.flurai.app.core.content.KanjiLevel
import com.flurai.app.core.content.LevelCount
import com.flurai.app.core.content.SentenceEntity
import com.flurai.app.core.content.WordEntity
import com.flurai.app.core.content.WordLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory StudyDao: just enough behaviour for the repository. */
class FakeStudyDao : StudyDao {
    val cards = MutableStateFlow<Map<Triple<ItemType, Int, CardKind>, CardEntity>>(emptyMap())
    val logs = mutableListOf<ReviewLogEntity>()
    private fun key(c: CardEntity) = Triple(c.itemType, c.itemId, c.kind)
    private fun all() = cards.value.values

    override suspend fun dueCards(uid: String, until: Long, now: Long, listening: Boolean) =
        all().filter { it.due < until && !it.suspended && (it.buriedUntil ?: 0) <= now && (listening || it.kind != CardKind.Listening) }.sortedBy { it.due }
    override fun dueCount(uid: String, until: Long, now: Long, listening: Boolean): Flow<Int> = cards.map { it.size }
    override suspend fun introducedItems(uid: String) = all().map { ItemRef(it.itemType, it.itemId) }.distinct()
    override fun introducedItemsFlow(uid: String) = cards.map { m -> m.values.map { ItemRef(it.itemType, it.itemId) }.distinct() }
    override fun introducedSince(uid: String, since: Long) =
        cards.map { logs.count { it.kind == CardKind.Recognition && it.stateBefore == 0 && it.reviewedAt >= since } }
    override fun cardsForItem(uid: String, itemType: ItemType, itemId: Int) =
        cards.map { m -> m.values.filter { it.itemType == itemType && it.itemId == itemId } }
    override fun allCards(uid: String) = cards.map { it.values.toList() }
    override suspend fun upsertCard(card: CardEntity) { cards.value = cards.value + (key(card) to card) }
    override suspend fun insertCardIfAbsent(card: CardEntity) {
        if (key(card) !in cards.value) upsertCard(card)
    }
    override suspend fun insertLog(log: ReviewLogEntity) { logs += log }
    override suspend fun setSuspended(uid: String, itemType: ItemType, itemId: Int, suspended: Boolean) {
        cards.value = cards.value.mapValues { (_, c) -> if (c.itemType == itemType && c.itemId == itemId) c.copy(suspended = suspended) else c }
    }
    override suspend fun bury(uid: String, itemType: ItemType, itemId: Int, until: Long) {
        cards.value = cards.value.mapValues { (_, c) -> if (c.itemType == itemType && c.itemId == itemId) c.copy(buriedUntil = until) else c }
    }
    override suspend fun resetItem(uid: String, itemType: ItemType, itemId: Int) {
        cards.value = cards.value.filterValues { !(it.itemType == itemType && it.itemId == itemId) }
    }
}

/** Content with N5 words 1–3 (word 3 is in Genki lesson 1, so it's taught first) and N4 words 10–11. */
class FakeContentDao : ContentDao {
    private val levels = mapOf(5 to listOf(3, 1, 2), 4 to listOf(10, 11))
    override suspend fun wordIdsInStudyOrder(jlpt: Int) = levels[jlpt].orEmpty()
    override suspend fun wordLevels() = levels.flatMap { (jlpt, ids) -> ids.map { WordLevel(it, jlpt) } }
    override fun wordCountsByLevel(): Flow<List<LevelCount>> = throw NotImplementedError()
    override fun kanjiCountsByLevel(): Flow<List<LevelCount>> = throw NotImplementedError()
    override fun wordsAtLevel(jlpt: Int): Flow<List<WordEntity>> = throw NotImplementedError()
    override fun searchWords(query: String): Flow<List<WordEntity>> = throw NotImplementedError()
    override suspend fun word(id: Int): WordEntity? = throw NotImplementedError()
    override suspend fun sentencesForWord(wordId: Int): List<SentenceEntity> = throw NotImplementedError()
    override fun kanjiAtLevel(jlpt: Int): Flow<List<KanjiEntity>> = throw NotImplementedError()
    override suspend fun readingCandidates() = emptyList<SentenceEntity>()
    override suspend fun allKanji(): List<KanjiEntity> = throw NotImplementedError()
    override fun grammarPoints() = kotlinx.coroutines.flow.flowOf(emptyList<com.flurai.app.core.content.GrammarPointEntity>())
    override suspend fun allGrammarPoints() = emptyList<com.flurai.app.core.content.GrammarPointEntity>()
    override suspend fun grammarPoint(id: Int): com.flurai.app.core.content.GrammarPointEntity? = null
    override suspend fun grammarExamples(id: Int) = emptyList<com.flurai.app.core.content.GrammarExampleEntity>()
    override suspend fun allGrammarExamples() = emptyList<com.flurai.app.core.content.GrammarExampleEntity>()
    override suspend fun allWords(): List<WordEntity> = throw NotImplementedError()
    override suspend fun kanjiLevels(): List<KanjiLevel> = throw NotImplementedError()
    override suspend fun words(ids: List<Int>): List<WordEntity> = throw NotImplementedError()
    override fun info(): Flow<List<ContentInfoEntity>> = throw NotImplementedError()
}
