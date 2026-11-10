package com.flurai.app.core.sync

import com.flurai.app.core.prefs.SyncCursors
import com.flurai.app.core.prefs.UserPreferences
import com.flurai.app.core.activity.studyEpochDay
import com.flurai.app.core.auth.GUEST_UID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val prefs: UserPreferences,
    private val clock: () -> Long,
    private val onError: (Throwable) -> Unit,
) {
    @Inject
    constructor(cloud: SyncCloud, local: SyncLocal, prefs: UserPreferences) :
        this(cloud, local, prefs, System::currentTimeMillis, { android.util.Log.w("SyncEngine", "sync failed", it) })

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
        val cursors = prefs.cursors(uid)
        val pulled = pull(uid, cursors.pull)
        push(uid, cursors.push, pulled)
        prefs.saveCursors(uid, SyncCursors(pull = pulled.maxUpdatedAt.takeIf { it > cursors.pull } ?: cursors.pull, push = startedAt))
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
    suspend fun markAllDirty(uid: String) = prefs.markAllDirty(uid)

    /** Deletes this user's cloud copy. Needs the user to still be signed in. */
    suspend fun deleteRemote(uid: String) = cloud.deleteAll(uid, CLOUD_COLLECTIONS)

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
            ROW_COLLECTIONS.map { name -> async { cloud.changedSince(uid, name, since) } }.awaitAll()
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
        if (!prefs.hasProfile(uid)) {
            val saved = cloud.document(uid, META_COLLECTION, "profile")?.get("prefs") as? Map<*, *>
            if (saved != null) prefs.import(uid, saved)
        }
        // Channels and decks: likewise, restored only when this phone has none.
        if (local.channels(uid).isEmpty()) {
            val items = cloud.document(uid, META_COLLECTION, "channels")?.get("items") as? List<*>
            items?.filterIsInstance<Map<*, *>>()?.mapNotNull { channelFrom(uid, it) }?.forEach { local.insertChannel(it) }
        }
        if (local.decks(uid).isEmpty()) {
            val items = cloud.document(uid, META_COLLECTION, "decks")?.get("items") as? List<*>
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
        val settings = prefs.export(uid)
        val metaHash = Triple(channels, settings, decks).hashCode().toLong()
        val metaChanged = since == 0L || prefs.uploadedHash(uid) != metaHash
        if (metaChanged) {
            writes += CloudWrite(META_COLLECTION, "channels", mapOf("items" to channels))
            writes += CloudWrite(META_COLLECTION, "decks", mapOf("items" to decks))
            if (settings.isNotEmpty()) writes += CloudWrite(META_COLLECTION, "profile", mapOf("prefs" to settings))
        }

        if (writes.isNotEmpty()) cloud.write(uid, writes)
        if (metaChanged) prefs.saveUploadedHash(uid, metaHash)
    }
}
