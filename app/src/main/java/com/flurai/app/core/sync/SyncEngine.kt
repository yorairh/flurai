package com.flurai.app.core.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.flurai.app.core.activity.studyEpochDay
import com.flurai.app.core.auth.GUEST_UID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** What the settings screen shows about the last sync. */
data class SyncStatus(val running: Boolean = false, val lastSyncedAt: Long? = null, val error: String? = null)

/**
 * Keeps a copy of the learner's progress in the cloud (users/{uid}/...) so it survives a new
 * phone or a reinstall. Local data stays the source of truth: each sync first pulls what changed
 * remotely and merges it (the newer row wins), then pushes what changed locally.
 *
 * Synced: flashcards, study days, test results, grammar progress, custom channels and decks, and
 * the profile/settings. Not synced: the review log (nothing reads it) and suspended/buried flags
 * set without a review.
 *
 * The cloud is reached through [SyncCloud] and this phone's database through [SyncLocal], so the
 * whole pull-merge-push runs against in-memory copies in tests. The rules for which copy wins and
 * what the documents look like are in SyncCodec.kt.
 */
@Singleton
class SyncEngine internal constructor(
    private val cloud: SyncCloud,
    private val local: SyncLocal,
    private val dataStore: DataStore<Preferences>,
    private val clock: () -> Long,
    private val onError: (Throwable) -> Unit,
) {
    @Inject
    constructor(cloud: SyncCloud, local: SyncLocal, dataStore: DataStore<Preferences>) :
        this(cloud, local, dataStore, System::currentTimeMillis, { android.util.Log.w("SyncEngine", "sync failed", it) })

    private val mutex = Mutex()
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /** Pulls then pushes. Never throws: a failure is recorded in [status] and the next sync retries. */
    suspend fun sync(uid: String): Boolean = if (uid == GUEST_UID) false else mutex.withLock {
        _status.value = _status.value.copy(running = true, error = null)
        try {
            try {
                syncOnce(uid)
            } catch (e: CloudException) {
                if (e.reason != CloudException.Reason.NotAllowed) throw e
                // The sign-in token may still carry an old "email not verified" claim: refresh it and retry once.
                cloud.refreshSignIn()
                syncOnce(uid)
            }
            _status.value = SyncStatus(lastSyncedAt = clock())
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e)
            _status.value = _status.value.copy(running = false, error = describe(e))
            false
        }
    }

    private suspend fun syncOnce(uid: String) {
        val startedAt = clock()
        val cursors = cursors(uid)
        val pulled = pull(uid, cursors.pull)
        push(uid, cursors.push, pulled)
        saveCursors(uid, pull = pulled.maxUpdatedAt.takeIf { it > cursors.pull } ?: cursors.pull, push = startedAt)
    }

    /** A message that says why, so a failure can be reported and fixed. Local data is never touched by a failed sync. */
    private fun describe(e: Exception): String {
        val reason = when ((e as? CloudException)?.reason) {
            CloudException.Reason.NotAllowed -> "not allowed (confirm your email, then sign out and in)"
            CloudException.Reason.Offline -> "no connection to the server"
            CloudException.Reason.NotFound -> "the cloud database was not found"
            CloudException.Reason.Other, null -> e.cause?.javaClass?.simpleName ?: e.javaClass.simpleName
        }
        return "Could not sync: $reason. Your data is safe on this phone."
    }

    /** Makes the next sync push everything (after progress was moved between accounts). */
    suspend fun markAllDirty(uid: String) {
        dataStore.edit { it[longPreferencesKey("$uid.sync.push")] = 0L }
    }

    /** Deletes this user's cloud copy. Needs the user to still be signed in. */
    suspend fun deleteRemote(uid: String) = cloud.deleteAll(uid, COLLECTIONS + META)

    // -- pull ------------------------------------------------------------------------------------

    /**
     * What the pull took from the cloud. Ids listed here already match the cloud copy, so the push
     * skips them; rows where the local copy won the merge are not listed and get uploaded.
     */
    private class Pulled(val maxUpdatedAt: Long, val cardIds: Set<String>, val dayIds: Set<Long>, val grammarIds: Set<Int>)

    private suspend fun pull(uid: String, since: Long): Pulled {
        var max = since
        val cardIds = HashSet<String>()
        val dayIds = HashSet<Long>()
        val grammarIds = HashSet<Int>()

        // The downloads are independent: start them together, then merge one by one.
        val (cardDocs, dayDocs, testDocs, grammarDocs) = coroutineScope {
            COLLECTIONS.map { name -> async { cloud.changedSince(uid, name, since) } }.awaitAll()
        }

        for (doc in cardDocs) {
            max = maxOf(max, doc.updatedAt)
            val card = cardFrom(uid, doc.data) ?: continue
            val mine = local.card(uid, card.itemType, card.itemId, card.kind)
            if (mine == null || touched(card) > touched(mine)) local.upsertCard(card)
            if (cloudCovers(touched(card), mine?.let(::touched))) cardIds += doc.id
        }
        for (doc in dayDocs) {
            max = maxOf(max, doc.updatedAt)
            val remote = dayFrom(uid, doc.data) ?: continue
            val merged = mergeDay(local.day(uid, remote.day), remote)
            local.upsertDay(merged)
            if (merged == remote) dayIds += remote.day
        }
        for (doc in testDocs) {
            max = maxOf(max, doc.updatedAt)
            val test = testFrom(uid, doc.data) ?: continue
            if (!local.hasTest(uid, test.type.name, test.takenAt)) local.insertTest(test)
        }
        for (doc in grammarDocs) {
            max = maxOf(max, doc.updatedAt)
            val remote = grammarFrom(uid, doc.data) ?: continue
            val mine = local.grammar(uid, remote.grammarId)
            if (mine == null || remote.lastAt > mine.lastAt) local.upsertGrammar(remote)
            if (cloudCovers(remote.lastAt, mine?.lastAt)) grammarIds += remote.grammarId
        }

        // Profile and settings: only restored onto a phone that has none yet (a fresh install).
        val hasProfile = dataStore.data.first().asMap().keys.any { it.name == "$uid.level" }
        if (!hasProfile) {
            val prefs = cloud.document(uid, META, "profile")?.get("prefs") as? Map<*, *>
            if (prefs != null) writePrefs(uid, prefs)
        }
        // Channels and decks: likewise, restored only when this phone has none.
        if (local.channels(uid).isEmpty()) {
            val items = cloud.document(uid, META, "channels")?.get("items") as? List<*>
            items?.filterIsInstance<Map<*, *>>()?.mapNotNull { channelFrom(uid, it) }?.forEach { local.insertChannel(it) }
        }
        if (local.decks(uid).isEmpty()) {
            val items = cloud.document(uid, META, "decks")?.get("items") as? List<*>
            items?.filterIsInstance<Map<*, *>>()?.mapNotNull { deckFrom(uid, it) }?.forEach { local.createDeck(it.deck, it.words, it.deck.createdAt) }
        }
        return Pulled(max, cardIds, dayIds, grammarIds)
    }

    // -- push ------------------------------------------------------------------------------------

    private suspend fun push(uid: String, since: Long, pulled: Pulled) {
        val writes = mutableListOf<CloudWrite>()

        local.cards(uid).filter { needsUpload(it, since) && cardId(it) !in pulled.cardIds }
            .forEach { writes += CloudWrite("cards", cardId(it), cardDoc(it)) }
        val sinceDay = if (since == 0L) 0L else studyEpochDay(Instant.ofEpochMilli(since)) - 1
        local.days(uid).filter { it.day >= sinceDay && it.day !in pulled.dayIds }
            .forEach { writes += CloudWrite("days", it.day.toString(), dayDoc(it)) }
        local.tests(uid).filter { it.takenAt > since }
            .forEach { writes += CloudWrite("tests", testId(it), testDoc(it)) }
        local.grammar(uid).filter { it.lastAt > since && it.grammarId !in pulled.grammarIds }
            .forEach { writes += CloudWrite("grammar", it.grammarId.toString(), grammarDoc(it)) }

        // Small whole-document items: written only when they changed since the last upload (or on a full push).
        val channels = local.channels(uid).map(::channelDoc)
        val decks = local.decks(uid).map { deckDoc(it, local.deckWordIds(it.id)) }
        val prefs = readPrefs(uid)
        val metaKey = longPreferencesKey("$uid.sync.meta")
        val metaHash = Triple(channels, prefs, decks).hashCode().toLong()
        val metaChanged = since == 0L || dataStore.data.first()[metaKey] != metaHash
        if (metaChanged) {
            writes += CloudWrite(META, "channels", mapOf("items" to channels))
            writes += CloudWrite(META, "decks", mapOf("items" to decks))
            if (prefs.isNotEmpty()) writes += CloudWrite(META, "profile", mapOf("prefs" to prefs))
        }

        if (writes.isNotEmpty()) cloud.write(uid, writes)
        if (metaChanged) dataStore.edit { it[metaKey] = metaHash }
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
        /** The collections that hold one document per row, in the order [pull] reads them. */
        val COLLECTIONS = listOf("cards", "days", "tests", "grammar")

        /** Small whole-document items (profile, channels, decks). */
        const val META = "meta"
    }
}
