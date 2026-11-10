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
        prefs.saveCursors(uid, SyncCursors(pull = pulled.maxUpdatedAt, push = startedAt))
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

    /**
     * Deletes this user's cloud copy. Needs the user to still be signed in. The next sync uploads
     * everything again, so an account that lives on (its deletion failed) gets its backup back.
     */
    suspend fun deleteRemote(uid: String) = mutex.withLock {
        prefs.markAllDirty(uid)
        cloud.deleteAll(uid, CLOUD_COLLECTIONS)
    }

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
        val (cardDocs, dayDocs, testDocs, grammarDocs, metaDocs) = coroutineScope {
            CLOUD_COLLECTIONS.map { name -> async { cloud.changedSince(uid, name, since) } }.awaitAll()
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
        // Channels and decks are whole documents: another phone's version replaces this phone's, unless
        // this phone changed its own since the last sync (then it wins and is uploaded).
        metaDocs.forEach { max = maxOf(max, it.updatedAt) }
        fun items(id: String) = (metaDocs.firstOrNull { it.id == id }?.data?.get("items") as? List<*>)?.filterIsInstance<Map<*, *>>()
        items("channels")?.let { cloudItems ->
            val theirs = cloudItems.mapNotNull { channelFrom(uid, it) }
            val mine = local.channels(uid)
            if (theirs.map(::channelDoc) != mine.map(::channelDoc) && mayTakeCloudVersion(uid, "channels", mine.map(::channelDoc))) {
                local.replaceChannels(uid, theirs)
                prefs.saveUploadedHash(uid, "channels", fingerprint(local.channels(uid).map(::channelDoc)))
            }
        }
        items("decks")?.let { cloudItems ->
            val theirs = cloudItems.mapNotNull { deckFrom(uid, it) }
            val mine = localDeckDocs(uid)
            if (theirs.map { deckDoc(it.deck, it.words) } != mine && mayTakeCloudVersion(uid, "decks", mine)) {
                local.replaceDecks(uid, theirs.map { it.deck to it.words })
                prefs.saveUploadedHash(uid, "decks", fingerprint(localDeckDocs(uid)))
            }
        }
        return Pulled(max, cardIds, dayIds, grammarIds)
    }

    private suspend fun localDeckDocs(uid: String) = local.decks(uid).map { deckDoc(it, local.deckWordIds(it.id)) }

    /**
     * Whether the cloud's version of a whole-document item [part] may replace this phone's [mine]:
     * this phone has not changed it since it last uploaded or took it (its fingerprint still
     * matches), or it never synced it and has nothing of its own. An item deleted here differs from
     * what was uploaded, so it is not brought back.
     */
    private suspend fun mayTakeCloudVersion(uid: String, part: String, mine: List<Map<String, Any?>>): Boolean {
        val uploaded = prefs.uploadedHash(uid, part)
        return if (uploaded == null) mine.isEmpty() else uploaded == fingerprint(mine)
    }

    private fun fingerprint(items: Any): Long = items.hashCode().toLong()

    // -- push ------------------------------------------------------------------------------------

    private suspend fun push(uid: String, since: Long, pulled: Pulled) {
        val writes = mutableListOf<CloudWrite>()

        local.cardsSince(uid, since).filter { needsUpload(it, since) && cardId(it) !in pulled.cardIds }
            .forEach { writes += CloudWrite("cards", cardId(it), cardDoc(it)) }
        val sinceDay = if (since == 0L) 0L else studyEpochDay(Instant.ofEpochMilli(since)) - 1
        local.days(uid).filter { it.day >= sinceDay && it.day !in pulled.dayIds }
            .forEach { writes += CloudWrite("days", it.day.toString(), dayDoc(it)) }
        local.testsSince(uid, since).filter { it.takenAt > since }
            .forEach { writes += CloudWrite("tests", testId(it), testDoc(it)) }
        local.grammarSince(uid, since).filter { it.lastAt > since && it.grammarId !in pulled.grammarIds }
            .forEach { writes += CloudWrite("grammar", it.grammarId.toString(), grammarDoc(it)) }

        // Small whole-document items: written only when they changed since the last upload (or on a full push).
        // Each is written only when it changed here since it was last uploaded or taken (or on a full push).
        val channels = local.channels(uid).map(::channelDoc)
        val decks = localDeckDocs(uid)
        val settings = prefs.export(uid)
        val parts = mapOf("channels" to channels, "decks" to decks, "settings" to settings)
        val changed = parts.filter { (part, value) -> since == 0L || prefs.uploadedHash(uid, part) != fingerprint(value) }.keys
        if ("channels" in changed) writes += CloudWrite(META_COLLECTION, "channels", mapOf("items" to channels))
        if ("decks" in changed) writes += CloudWrite(META_COLLECTION, "decks", mapOf("items" to decks))
        if ("settings" in changed && settings.isNotEmpty()) writes += CloudWrite(META_COLLECTION, "profile", mapOf("prefs" to settings))

        if (writes.isNotEmpty()) cloud.write(uid, writes)
        changed.forEach { prefs.saveUploadedHash(uid, it, fingerprint(parts.getValue(it))) }
    }
}
