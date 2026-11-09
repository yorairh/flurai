package com.flurai.app.core.study

import com.flurai.app.core.content.ContentDao
import com.flurai.app.core.content.ContentInfoEntity
import com.flurai.app.core.content.KanjiEntity
import com.flurai.app.core.content.KanjiLevel
import com.flurai.app.core.content.LevelCount
import com.flurai.app.core.content.SentenceEntity
import com.flurai.app.core.content.WordEntity
import com.flurai.app.core.content.WordLevel
import com.flurai.app.core.model.JlptLevel
import com.flurai.app.core.profile.StudyProfile
import com.flurai.app.core.srs.Rating
import com.flurai.app.core.srs.SrsState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant

class StudyRepositoryTest {
    private val now = Instant.parse("2026-03-10T03:00:00Z")

    /** In-memory StudyDao: just enough behaviour for the repository. */
    private class FakeStudyDao : StudyDao {
        val cards = MutableStateFlow<Map<Triple<ItemType, Int, CardKind>, CardEntity>>(emptyMap())
        val logs = mutableListOf<ReviewLogEntity>()
        private fun key(c: CardEntity) = Triple(c.itemType, c.itemId, c.kind)
        private fun all() = cards.value.values

        override suspend fun dueCards(uid: String, until: Long, now: Long) =
            all().filter { it.due <= until && !it.suspended && (it.buriedUntil ?: 0) <= now }.sortedBy { it.due }
        override fun dueCount(uid: String, until: Long, now: Long): Flow<Int> = cards.map { it.size }
        override suspend fun introducedItems(uid: String) = all().map { ItemRef(it.itemType, it.itemId) }.distinct()
        override fun introducedItemsFlow(uid: String) = cards.map { m -> m.values.map { ItemRef(it.itemType, it.itemId) }.distinct() }
        override fun introducedSince(uid: String, since: Long) =
            cards.map { m -> m.values.count { it.kind == CardKind.Recognition && it.createdAt >= since } }
        override fun cardsForItem(uid: String, itemType: ItemType, itemId: Int) =
            cards.map { m -> m.values.filter { it.itemType == itemType && it.itemId == itemId } }
        override fun allCards(uid: String) = cards.map { it.values.toList() }
        override fun stateCounts(uid: String): Flow<List<StateCount>> = throw NotImplementedError()
        override fun reviewsSince(uid: String, since: Long): Flow<Int> = throw NotImplementedError()
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
    private class FakeContentDao : ContentDao {
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
        override suspend fun readingCandidates() = emptyList<com.flurai.app.core.content.SentenceEntity>()
        override suspend fun allKanji(): List<com.flurai.app.core.content.KanjiEntity> = throw NotImplementedError()
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

    private val dao = FakeStudyDao()
    private val repo = StudyRepository(dao, FakeContentDao())

    private fun profile(level: JlptLevel, newPerDay: Int = 10, hiragana: Boolean = true, katakana: Boolean = true) =
        StudyProfile(level, 15, newPerDay, hiragana, katakana)

    @Test
    fun beginnersWhoCantReadKanaStartWithHiragana() = runTest {
        val session = repo.loadSession("u", profile(JlptLevel.Beginner, newPerDay = 3, hiragana = false), StudySource.Auto, now)
        assertEquals(listOf(1, 2, 3), session.newItems.map { it.itemId })
        assertEquals(setOf(ItemType.Kana), session.newItems.map { it.itemType }.toSet())
    }

    @Test
    fun autoTakesWordsFromTheStartingLevelInGenkiOrderThenMovesOn() = runTest {
        val n5 = repo.loadSession("u", profile(JlptLevel.N5), StudySource.Auto, now)
        assertEquals(listOf(3, 1, 2), n5.newItems.map { it.itemId })

        n5.newItems.forEach { repo.answer("u", QueueItem.New(it), Rating.Good, now, Duration.ZERO) }
        val later = repo.loadSession("u", profile(JlptLevel.N5), StudySource.Auto, now)
        assertEquals(listOf(10, 11), later.newItems.map { it.itemId })
    }

    @Test
    fun dailyNewLimitCountsWordsAlreadyIntroducedToday() = runTest {
        val first = repo.loadSession("u", profile(JlptLevel.N5, newPerDay = 2), StudySource.Auto, now)
        assertEquals(2, first.newItems.size)
        repo.answer("u", QueueItem.New(first.newItems[0]), Rating.Good, now, Duration.ZERO)
        val second = repo.loadSession("u", profile(JlptLevel.N5, newPerDay = 2), StudySource.Auto, now)
        assertEquals(1, second.newItems.size)
    }

    @Test
    fun learningAWordUnlocksItsProductionCardForTomorrow() = runTest {
        val word = ItemRef(ItemType.Word, 1)
        val learned = repo.answer("u", QueueItem.New(word), Rating.Easy, now, Duration.ZERO)
        assertEquals(SrsState.Review.value, learned.state)
        val production = dao.cards.value[Triple(ItemType.Word, 1, CardKind.Production)]
        assertNotNull(production)
        assertEquals(StudyDay.end(now).toEpochMilli(), production!!.due)
        assertNull(production.lastReview)
    }

    @Test
    fun kanaNeverGetProductionCards() = runTest {
        repo.answer("u", QueueItem.New(ItemRef(ItemType.Kana, 1)), Rating.Easy, now, Duration.ZERO)
        assertEquals(1, dao.cards.value.size)
    }

    @Test
    fun forgettingAReviewCountsALapseAndLogsTheAnswer() = runTest {
        val item = QueueItem.New(ItemRef(ItemType.Word, 2))
        val learned = repo.answer("u", item, Rating.Easy, now, Duration.ZERO)
        val later = now.plus(Duration.ofDays(9))
        val forgotten = repo.answer("u", QueueItem.Existing(learned), Rating.Again, later, Duration.ofSeconds(4))
        assertEquals(1, forgotten.lapses)
        assertEquals(SrsState.Relearning.value, forgotten.state)
        assertEquals(listOf(0, SrsState.Review.value), dao.logs.map { it.stateBefore })
        assertEquals(4000, dao.logs.last().durationMs)
    }

    @Test
    fun suspendedAndResetWordsLeaveTheQueue() = runTest {
        val word = ItemRef(ItemType.Word, 1)
        repo.answer("u", QueueItem.New(word), Rating.Again, now, Duration.ZERO)
        repo.setSuspended("u", word, true)
        assertEquals(0, repo.loadSession("u", profile(JlptLevel.N5), StudySource.Level(5), now.plusSeconds(120)).cards.size)
        repo.reset("u", word)
        val session = repo.loadSession("u", profile(JlptLevel.N5), StudySource.Level(5), now)
        assertEquals(listOf(3, 1, 2), session.newItems.map { it.itemId }) // word 1 is new again
    }

    @Test
    fun studySourceSurvivesNavigation() {
        listOf(StudySource.Auto, StudySource.Level(3), StudySource.KanaDeck(KanaScript.Katakana)).forEach {
            assertEquals(it, StudySource.decode(it.encode()))
        }
    }
}
