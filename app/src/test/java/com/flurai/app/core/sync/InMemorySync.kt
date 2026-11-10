package com.flurai.app.core.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.flurai.app.core.activity.StudyDayEntity
import com.flurai.app.core.grammar.GrammarProgressEntity
import com.flurai.app.core.quiz.TestResultEntity
import com.flurai.app.core.study.CardEntity
import com.flurai.app.core.study.CardKind
import com.flurai.app.core.study.DeckEntity
import com.flurai.app.core.study.ItemType
import com.flurai.app.core.video.CustomChannelEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A cloud kept in memory, shared by every [Phone] of a test. Like Firestore it stamps each write
 * with its own time (`serverTime`, which only moves when a test says so) and hands numbers back as
 * Long/Double whatever type they were written as.
 */
class InMemoryCloud : SyncCloud {
    private class Stored(val data: Map<String, Any?>, val updatedAt: Long)

    private val docs = LinkedHashMap<Triple<String, String, String>, Stored>()
    private val failures = ArrayDeque<CloudException>()

    var serverTime = 1_000L
    var refreshCount = 0
        private set

    /** The next [times] calls fail with [reason]; a negative count fails every call until [heal]. */
    var failingFor: CloudException? = null
    fun failNext(reason: CloudException.Reason, times: Int = 1) {
        repeat(times) { failures += CloudException(reason) }
    }

    fun failAlways(reason: CloudException.Reason) { failingFor = CloudException(reason) }
    fun heal() { failingFor = null; failures.clear() }

    private fun maybeFail() {
        failingFor?.let { throw it }
        failures.removeFirstOrNull()?.let { throw it }
    }

    fun ids(uid: String, collection: String): Set<String> =
        docs.filterKeys { it.first == uid && it.second == collection }.keys.map { it.third }.toSet()

    fun doc(uid: String, collection: String, id: String): Map<String, Any?>? = docs[Triple(uid, collection, id)]?.data

    override suspend fun changedSince(uid: String, collection: String, since: Long): List<CloudDoc> {
        maybeFail()
        return docs.filter { (k, v) -> k.first == uid && k.second == collection && (since == 0L || v.updatedAt > since) }
            .map { (k, v) -> CloudDoc(k.third, v.data, v.updatedAt) }
    }

    override suspend fun document(uid: String, collection: String, id: String): Map<String, Any?>? {
        maybeFail()
        return docs[Triple(uid, collection, id)]?.data
    }

    override suspend fun write(uid: String, docs: List<CloudWrite>) {
        maybeFail()
        docs.forEach { this.docs[Triple(uid, it.collection, it.id)] = Stored(normalise(it.data) as Map<String, Any?>, serverTime) }
    }

    override suspend fun deleteAll(uid: String, collections: List<String>) {
        maybeFail()
        docs.keys.removeAll { it.first == uid && it.second in collections }
    }

    override suspend fun refreshSignIn() {
        refreshCount++
    }

    private fun normalise(v: Any?): Any? = when (v) {
        is Int, is Short, is Byte -> (v as Number).toLong()
        is Float -> v.toDouble()
        is Map<*, *> -> v.entries.associate { (k, x) -> k to normalise(x) }
        is List<*> -> v.map(::normalise)
        else -> v
    }
}

/** A phone's database in memory. */
class InMemoryLocal : SyncLocal {
    val cardRows = mutableListOf<CardEntity>()
    val dayRows = mutableListOf<StudyDayEntity>()
    val testRows = mutableListOf<TestResultEntity>()
    val grammarRows = mutableListOf<GrammarProgressEntity>()
    val channelRows = mutableListOf<CustomChannelEntity>()
    val deckRows = mutableListOf<DeckEntity>()
    val deckWords = mutableMapOf<Long, List<Int>>()
    private var nextDeckId = 1L

    override suspend fun cards(uid: String) = cardRows.filter { it.uid == uid }
    override suspend fun card(uid: String, type: ItemType, id: Int, kind: CardKind) =
        cardRows.firstOrNull { it.uid == uid && it.itemType == type && it.itemId == id && it.kind == kind }

    override suspend fun upsertCard(card: CardEntity) {
        cardRows.removeAll { it.uid == card.uid && it.itemType == card.itemType && it.itemId == card.itemId && it.kind == card.kind }
        cardRows += card
    }

    override suspend fun days(uid: String) = dayRows.filter { it.uid == uid }
    override suspend fun day(uid: String, day: Long) = dayRows.firstOrNull { it.uid == uid && it.day == day }
    override suspend fun upsertDay(day: StudyDayEntity) {
        dayRows.removeAll { it.uid == day.uid && it.day == day.day }
        dayRows += day
    }

    override suspend fun tests(uid: String) = testRows.filter { it.uid == uid }
    override suspend fun hasTest(uid: String, type: String, takenAt: Long) =
        testRows.any { it.uid == uid && it.type.name == type && it.takenAt == takenAt }

    override suspend fun insertTest(result: TestResultEntity) { testRows += result }

    override suspend fun grammar(uid: String) = grammarRows.filter { it.uid == uid }
    override suspend fun grammar(uid: String, id: Int) = grammarRows.firstOrNull { it.uid == uid && it.grammarId == id }
    override suspend fun upsertGrammar(progress: GrammarProgressEntity) {
        grammarRows.removeAll { it.uid == progress.uid && it.grammarId == progress.grammarId }
        grammarRows += progress
    }

    override suspend fun channels(uid: String) = channelRows.filter { it.uid == uid }
    override suspend fun insertChannel(channel: CustomChannelEntity) { channelRows += channel }

    override suspend fun decks(uid: String) = deckRows.filter { it.uid == uid }
    override suspend fun deckWordIds(deckId: Long) = deckWords[deckId].orEmpty()
    override suspend fun createDeck(deck: DeckEntity, wordIds: List<Int>, now: Long) {
        val id = nextDeckId++
        deckRows += deck.copy(id = id)
        deckWords[id] = wordIds
    }
}

/** Settings kept in memory (the real file-backed store cannot rename its file on Windows while it is read). */
class InMemoryPrefs : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        transform(state.value).also { state.value = it }
}
