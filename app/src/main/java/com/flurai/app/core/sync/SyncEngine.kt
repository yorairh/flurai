package com.flurai.app.core.sync

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.flurai.app.core.activity.StudyDayDao
import com.flurai.app.core.activity.StudyDayEntity
import com.flurai.app.core.grammar.GrammarProgressDao
import com.flurai.app.core.grammar.GrammarProgressEntity
import com.flurai.app.core.quiz.TestResultDao
import com.flurai.app.core.quiz.TestResultEntity
import com.flurai.app.core.quiz.TestType
import com.flurai.app.core.study.CardEntity
import com.flurai.app.core.study.CardKind
import com.flurai.app.core.study.ItemType
import com.flurai.app.core.video.CustomChannelDao
import com.flurai.app.core.video.CustomChannelEntity
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/** What the settings screen shows about the last sync. */
data class SyncStatus(val running: Boolean = false, val lastSyncedAt: Long? = null, val error: String? = null)

/**
 * Keeps a copy of the learner's progress in Firestore (users/{uid}/...) so it survives a new
 * phone or a reinstall. Local data stays the source of truth: each sync first pulls what changed
 * remotely and merges it (the newer row wins), then pushes what changed locally.
 *
 * Synced: flashcards, study days, test results, grammar progress, custom channels, and the
 * profile/settings. Not synced: the review log (nothing reads it) and suspended/buried flags
 * set without a review.
 */
@Singleton
class SyncEngine @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val dao: SyncDao,
    private val studyDays: StudyDayDao,
    private val tests: TestResultDao,
    private val grammar: GrammarProgressDao,
    private val channels: CustomChannelDao,
    private val dataStore: DataStore<Preferences>,
) {
    private val mutex = Mutex()
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private fun user(uid: String) = firestore.collection("users").document(uid)

    /** Pulls then pushes. Never throws: a failure is recorded in [status] and the next sync retries. */
    suspend fun sync(uid: String): Boolean = mutex.withLock {
        _status.value = _status.value.copy(running = true, error = null)
        try {
            val startedAt = System.currentTimeMillis()
            val cursors = cursors(uid)
            val pulled = pull(uid, cursors.pull)
            push(uid, cursors.push, pulled)
            saveCursors(uid, pull = pulled.maxUpdatedAt.takeIf { it > cursors.pull } ?: cursors.pull, push = startedAt)
            _status.value = SyncStatus(lastSyncedAt = System.currentTimeMillis())
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "sync failed", e)
            _status.value = _status.value.copy(running = false, error = "Could not sync. Your data is safe on this phone.")
            false
        }
    }

    /** Makes the next sync push everything (after progress was moved between accounts). */
    suspend fun markAllDirty(uid: String) {
        dataStore.edit { it[longPreferencesKey("$uid.sync.push")] = 0L }
    }

    /** Deletes this user's cloud copy. Needs the user to still be signed in. */
    suspend fun deleteRemote(uid: String) {
        for (name in listOf("cards", "days", "tests", "grammar", "meta")) {
            val docs = user(uid).collection(name).get().await().documents
            docs.chunked(400).forEach { chunk ->
                firestore.batch().also { b -> chunk.forEach { b.delete(it.reference) } }.commit().await()
            }
        }
    }

    // -- pull ------------------------------------------------------------------------------------

    private class Pulled(val maxUpdatedAt: Long, val cardIds: Set<String>, val dayIds: Set<Long>, val grammarIds: Set<Int>)

    private suspend fun pull(uid: String, since: Long): Pulled {
        var max = since
        val cardIds = HashSet<String>()
        val dayIds = HashSet<Long>()
        val grammarIds = HashSet<Int>()

        suspend fun docs(name: String) = user(uid).collection(name).whereGreaterThan("updatedAt", since)
            .orderBy("updatedAt", Query.Direction.ASCENDING).get().await().documents

        for (doc in docs("cards")) {
            max = maxOf(max, doc.getLong("updatedAt") ?: 0)
            val card = cardFrom(uid, doc.data ?: continue) ?: continue
            val local = dao.card(uid, card.itemType, card.itemId, card.kind)
            if (local == null || touched(card) > touched(local)) dao.upsertCard(card)
            cardIds += doc.id
        }
        for (doc in docs("days")) {
            max = maxOf(max, doc.getLong("updatedAt") ?: 0)
            val day = doc.getLong("day") ?: continue
            val remote = StudyDayEntity(uid, day, (doc.getLong("seconds") ?: 0).toInt(), (doc.getLong("reviews") ?: 0).toInt())
            val local = dao.day(uid, day)
            // Time studied on two phones on the same day: keep the larger total, never lose time.
            dao.upsertDay(local?.let { StudyDayEntity(uid, day, maxOf(it.seconds, remote.seconds), maxOf(it.reviews, remote.reviews)) } ?: remote)
            dayIds += day
        }
        for (doc in docs("tests")) {
            max = maxOf(max, doc.getLong("updatedAt") ?: 0)
            val takenAt = doc.getLong("takenAt") ?: continue
            val type = doc.getString("type") ?: continue
            if (dao.testCount(uid, type, takenAt) == 0) {
                val kind = TestType.entries.firstOrNull { it.name == type } ?: continue
                tests.insert(
                    TestResultEntity(
                        uid = uid, type = kind, level = doc.getLong("level")?.toInt(),
                        correct = (doc.getLong("correct") ?: 0).toInt(), total = (doc.getLong("total") ?: 0).toInt(), takenAt = takenAt,
                    ),
                )
            }
        }
        for (doc in docs("grammar")) {
            max = maxOf(max, doc.getLong("updatedAt") ?: 0)
            val id = (doc.getLong("grammarId") ?: continue).toInt()
            val remote = GrammarProgressEntity(
                uid, id, (doc.getLong("correct") ?: 0).toInt(), (doc.getLong("wrong") ?: 0).toInt(),
                doc.getBoolean("lastCorrect") ?: false, doc.getLong("lastAt") ?: 0,
            )
            val local = grammar.get(uid, id)
            if (local == null || remote.lastAt > local.lastAt) grammar.upsert(remote)
            grammarIds += id
        }

        // Profile and settings: only restored onto a phone that has none yet (a fresh install).
        val hasProfile = dataStore.data.first().asMap().keys.any { it.name == "$uid.level" }
        if (!hasProfile) {
            val prefs = user(uid).collection("meta").document("profile").get().await().get("prefs") as? Map<*, *>
            if (prefs != null) writePrefs(uid, prefs)
        }
        // Channels: likewise, restored only when this phone has none.
        if (dao.channels(uid).isEmpty()) {
            val items = user(uid).collection("meta").document("channels").get().await().get("items") as? List<*>
            items?.filterIsInstance<Map<*, *>>()?.forEach { m ->
                channels.insert(
                    CustomChannelEntity(
                        uid = uid, name = m["name"] as? String ?: return@forEach, url = m["url"] as? String ?: return@forEach,
                        level = (m["level"] as? Long)?.toInt() ?: 5, addedAt = m["addedAt"] as? Long ?: 0,
                    ),
                )
            }
        }
        return Pulled(max, cardIds, dayIds, grammarIds)
    }

    // -- push ------------------------------------------------------------------------------------

    private suspend fun push(uid: String, since: Long, pulled: Pulled) {
        val now = System.currentTimeMillis()
        val writes = mutableListOf<Pair<com.google.firebase.firestore.DocumentReference, Map<String, Any?>>>()

        dao.cardsChangedSince(uid, since).filter { cardId(it) !in pulled.cardIds }.forEach { c ->
            writes += user(uid).collection("cards").document(cardId(c)) to mapOf(
                "itemType" to c.itemType.name, "itemId" to c.itemId, "kind" to c.kind.name, "state" to c.state, "step" to c.step,
                "stability" to c.stability, "difficulty" to c.difficulty, "due" to c.due, "lastReview" to c.lastReview,
                "reps" to c.reps, "lapses" to c.lapses, "suspended" to c.suspended, "buriedUntil" to c.buriedUntil,
                "createdAt" to c.createdAt, "updatedAt" to now,
            )
        }
        val sinceDay = if (since == 0L) 0L else com.flurai.app.core.activity.studyEpochDay(java.time.Instant.ofEpochMilli(since)) - 1
        studyDays.all(uid).filter { it.day >= sinceDay && it.day !in pulled.dayIds }.forEach { d ->
            writes += user(uid).collection("days").document(d.day.toString()) to
                mapOf("day" to d.day, "seconds" to d.seconds, "reviews" to d.reviews, "updatedAt" to now)
        }
        dao.testsSince(uid, since).forEach { t ->
            writes += user(uid).collection("tests").document("${t.takenAt}-${t.type.name}") to mapOf(
                "type" to t.type.name, "level" to t.level, "correct" to t.correct, "total" to t.total, "takenAt" to t.takenAt, "updatedAt" to now,
            )
        }
        dao.grammarSince(uid, since).filter { it.grammarId !in pulled.grammarIds }.forEach { g ->
            writes += user(uid).collection("grammar").document(g.grammarId.toString()) to mapOf(
                "grammarId" to g.grammarId, "correct" to g.correct, "wrong" to g.wrong, "lastCorrect" to g.lastCorrect,
                "lastAt" to g.lastAt, "updatedAt" to now,
            )
        }
        writes.chunked(400).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { (ref, data) -> batch.set(ref, data) }
            batch.commit().await()
        }

        // Small whole-document items: written on every sync.
        user(uid).collection("meta").document("channels").set(
            mapOf(
                "items" to dao.channels(uid).map { mapOf("name" to it.name, "url" to it.url, "level" to it.level, "addedAt" to it.addedAt) },
                "updatedAt" to now,
            ),
        ).await()
        val prefs = readPrefs(uid)
        if (prefs.isNotEmpty()) user(uid).collection("meta").document("profile").set(mapOf("prefs" to prefs, "updatedAt" to now)).await()
    }

    // -- preferences -----------------------------------------------------------------------------

    /** Profile and settings keys of [uid] without the uid prefix, skipping the sync cursors. */
    private suspend fun readPrefs(uid: String): Map<String, Any> =
        dataStore.data.first().asMap().entries
            .filter { it.key.name.startsWith("$uid.") && !it.key.name.startsWith("$uid.sync.") }
            .associate { it.key.name.removePrefix("$uid.") to it.value }

    private suspend fun writePrefs(uid: String, values: Map<*, *>) {
        dataStore.edit { prefs ->
            values.forEach { (k, v) ->
                val name = "$uid.$k"
                when (v) {
                    is Boolean -> prefs[booleanPreferencesKey(name)] = v
                    is Long -> prefs[intPreferencesKey(name)] = v.toInt()
                    is String -> prefs[stringPreferencesKey(name)] = v
                }
            }
        }
    }

    // -- cursors ---------------------------------------------------------------------------------

    private class Cursors(val pull: Long, val push: Long)

    private suspend fun cursors(uid: String): Cursors {
        val prefs = dataStore.data.first()
        return Cursors(prefs[longPreferencesKey("$uid.sync.pull")] ?: 0L, prefs[longPreferencesKey("$uid.sync.push")] ?: 0L)
    }

    private suspend fun saveCursors(uid: String, pull: Long, push: Long) {
        dataStore.edit {
            it[longPreferencesKey("$uid.sync.pull")] = pull
            it[longPreferencesKey("$uid.sync.push")] = push
        }
    }

    private companion object {
        const val TAG = "SyncEngine"
    }
}

internal fun cardId(c: CardEntity) = "${c.itemType.name}-${c.itemId}-${c.kind.name}"

/** When a card last changed: its latest review, or its creation. */
internal fun touched(c: CardEntity) = maxOf(c.lastReview ?: 0L, c.createdAt)

internal fun cardFrom(uid: String, d: Map<String, Any?>): CardEntity? {
    val type = ItemType.entries.firstOrNull { it.name == d["itemType"] } ?: return null
    val kind = CardKind.entries.firstOrNull { it.name == d["kind"] } ?: return null
    fun long(key: String) = (d[key] as? Number)?.toLong()
    return CardEntity(
        uid = uid, itemType = type, itemId = (d["itemId"] as? Number)?.toInt() ?: return null, kind = kind,
        state = (d["state"] as? Number)?.toInt() ?: return null, step = (d["step"] as? Number)?.toInt(),
        stability = (d["stability"] as? Number)?.toDouble(), difficulty = (d["difficulty"] as? Number)?.toDouble(),
        due = long("due") ?: return null, lastReview = long("lastReview"), reps = (d["reps"] as? Number)?.toInt() ?: 0,
        lapses = (d["lapses"] as? Number)?.toInt() ?: 0, suspended = d["suspended"] as? Boolean ?: false,
        buriedUntil = long("buriedUntil"), createdAt = long("createdAt") ?: 0L,
    )
}
