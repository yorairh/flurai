package com.flurai.app.core.sync

import com.flurai.app.core.activity.StudyDayDao
import com.flurai.app.core.activity.StudyDayEntity
import com.flurai.app.core.grammar.GrammarProgressDao
import com.flurai.app.core.grammar.GrammarProgressEntity
import com.flurai.app.core.quiz.TestResultDao
import com.flurai.app.core.quiz.TestResultEntity
import com.flurai.app.core.study.CardEntity
import com.flurai.app.core.study.CardKind
import com.flurai.app.core.study.DeckDao
import com.flurai.app.core.study.DeckEntity
import com.flurai.app.core.study.ItemType
import com.flurai.app.core.video.CustomChannelDao
import com.flurai.app.core.video.CustomChannelEntity
import javax.inject.Inject

/**
 * What the sync engine reads and writes on this phone: plain lookups and writes with no rules.
 * Which rows count as changed, and which copy wins, are decided by the engine, so they are tested
 * once. The "since" reads only narrow what is loaded: they return at least the rows changed after
 * that time and may return more (the in-memory copy returns everything). [RoomSyncLocal] in the app, an in-memory copy in tests.
 */
interface SyncLocal {
    suspend fun cardsSince(uid: String, since: Long): List<CardEntity>
    suspend fun card(uid: String, type: ItemType, id: Int, kind: CardKind): CardEntity?
    suspend fun upsertCard(card: CardEntity)

    suspend fun days(uid: String): List<StudyDayEntity>
    suspend fun day(uid: String, day: Long): StudyDayEntity?
    suspend fun upsertDay(day: StudyDayEntity)

    suspend fun testsSince(uid: String, since: Long): List<TestResultEntity>
    suspend fun hasTest(uid: String, type: String, takenAt: Long): Boolean
    suspend fun insertTest(result: TestResultEntity)

    suspend fun grammarSince(uid: String, since: Long): List<GrammarProgressEntity>
    suspend fun grammar(uid: String, id: Int): GrammarProgressEntity?
    suspend fun upsertGrammar(progress: GrammarProgressEntity)

    suspend fun channels(uid: String): List<CustomChannelEntity>
    suspend fun insertChannel(channel: CustomChannelEntity)

    suspend fun decks(uid: String): List<DeckEntity>
    suspend fun deckWordIds(deckId: Long): List<Int>
    suspend fun createDeck(deck: DeckEntity, wordIds: List<Int>, now: Long)
}

/** [SyncLocal] on the user database. */
class RoomSyncLocal @Inject constructor(
    private val sync: SyncDao,
    private val studyDays: StudyDayDao,
    private val testResults: TestResultDao,
    private val grammarProgress: GrammarProgressDao,
    private val customChannels: CustomChannelDao,
    private val deckDao: DeckDao,
) : SyncLocal {
    override suspend fun cardsSince(uid: String, since: Long) = sync.cardsSince(uid, since)
    override suspend fun card(uid: String, type: ItemType, id: Int, kind: CardKind) = sync.card(uid, type, id, kind)
    override suspend fun upsertCard(card: CardEntity) = sync.upsertCard(card)

    override suspend fun days(uid: String) = studyDays.all(uid)
    override suspend fun day(uid: String, day: Long) = sync.day(uid, day)
    override suspend fun upsertDay(day: StudyDayEntity) = sync.upsertDay(day)

    override suspend fun testsSince(uid: String, since: Long) = sync.testsSince(uid, since)
    override suspend fun hasTest(uid: String, type: String, takenAt: Long) = sync.testCount(uid, type, takenAt) > 0
    override suspend fun insertTest(result: TestResultEntity) = testResults.insert(result)

    override suspend fun grammarSince(uid: String, since: Long) = sync.grammarSince(uid, since)
    override suspend fun grammar(uid: String, id: Int) = grammarProgress.get(uid, id)
    override suspend fun upsertGrammar(progress: GrammarProgressEntity) = grammarProgress.upsert(progress)

    override suspend fun channels(uid: String) = sync.channels(uid)
    override suspend fun insertChannel(channel: CustomChannelEntity) = customChannels.insert(channel)

    override suspend fun decks(uid: String) = deckDao.decksNow(uid)
    override suspend fun deckWordIds(deckId: Long) = deckDao.wordIds(deckId)
    override suspend fun createDeck(deck: DeckEntity, wordIds: List<Int>, now: Long) {
        deckDao.create(deck, wordIds, now)
    }
}
