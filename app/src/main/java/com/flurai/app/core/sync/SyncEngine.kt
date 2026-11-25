package com.flurai.app.core.sync

import com.flurai.app.core.prefs.SyncCursors
import com.flurai.app.core.prefs.UserPreferences
import com.flurai.app.core.activity.studyEpochDay
import com.flurai.app.core.auth.GUEST_UID
import com.flurai.app.core.study.ItemType
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

/**
 * What the settings screen shows about the last sync. [pendingUploads]: flashcards the last sync left for
 * the next one (a push sends at most [SyncEngine.MAX_PUSH] documents, see there).
 */
data class SyncStatus(val running: Boolean = false, val lastSyncedAt: Long? = null, val error: String? = null, val pendingUploads: Int = 0)

/**
 * Keeps a copy of the learner's progress in the cloud (users/{uid}/...) so it survives a new
 * phone or a reinstall. Local data stays the source of truth: each sync first pulls what changed
 * remotely and merges it (the newer row wins), then pushes what changed locally.
 *
 * Synced: flashcards, study days, test results, grammar progress, grammar course progress, custom channels and decks, and
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
    /** The id to keep for a word id: a dictionary word that became a built-in word maps to it (WordSource.resolve). */
    private val resolveWord: suspend (Int) -> Int = { it },
) {
    @Inject
    constructor(cloud: SyncCloud, local: SyncLocal, prefs: UserPreferences, words: com.flurai.app.core.dictionary.WordSource) :
        this(cloud, local, prefs, System::currentTimeMillis, { android.util.Log.w("SyncEngine", "sync failed", it) }, { id -> words.resolve(id) })

    /** The backup was written by a newer FluRai in a format this version cannot read safely. */
    class NeedsUpdateException : Exception("backup written by a newer version")

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
            _status.value = SyncStatus(lastSyncedAt = clock(), pendingUploads = prefs.pushLeft(uid))
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
        val schema = cloud.document(uid, META_COLLECTION, SCHEMA_DOC)
        if (!formatSupported(schema)) throw NeedsUpdateException()
        val decksAsDocuments = backupFormat(schema) >= DECKS_AS_DOCUMENTS
        val startedAt = clock()
        val cursors = prefs.cursors(uid)
        val pulled = pull(uid, cursors.pull, decksAsDocuments)
        val pushedUpTo = push(uid, cursors.push, pulled, decksAsDocuments)
        prefs.saveCursors(uid, SyncCursors(pull = pulled.maxUpdatedAt, push = pushedUpTo ?: startedAt))
    }

    /** A message that says why, so a failure can be reported and fixed. Local data is never touched by a failed sync. */
    private fun describe(e: Exception): String {
        if (e is NeedsUpdateException) return "Update FluRai to keep syncing: your backup was saved by a newer version. Your data is safe on this phone."
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
    private class Pulled(
        val maxUpdatedAt: Long,
        val cardIds: Set<String>,
        val dayIds: Set<Long>,
        val grammarIds: Set<Int>,
        val courseIds: Set<Int>,
        val customWordIds: Set<Int>,
    )

    private suspend fun pull(uid: String, since: Long, decksAsDocuments: Boolean): Pulled {
        var max = since
        val cardIds = HashSet<String>()
        val dayIds = HashSet<Long>()
        val grammarIds = HashSet<Int>()
        val courseIds = HashSet<Int>()
        val customWordIds = HashSet<Int>()

        // The downloads are independent: start them together, then merge one by one.
        val downloaded = coroutineScope {
            CLOUD_COLLECTIONS.map { name -> async { cloud.changedSince(uid, name, since) } }.awaitAll()
        }
        val cardDocs = downloaded[0]
        val dayDocs = downloaded[1]
        val testDocs = downloaded[2]
        val grammarDocs = downloaded[3]
        val courseDocs = downloaded[4]
        val metaDocs = downloaded[5]
        val deckDocs = downloaded[6]
        val customWordDocs = downloaded[7]

        for (doc in cardDocs) {
            max = maxOf(max, doc.updatedAt)
            val stored = cardFrom(uid, doc.data) ?: continue
            // A dictionary word that has since become a built-in word is merged into it (see WordMerger).
            val card = if (stored.itemType == ItemType.Word) stored.copy(itemId = resolveWord(stored.itemId)) else stored
            val mine = local.card(uid, card.itemType, card.itemId, card.kind)
            if (mine == null || touched(card) > touched(mine)) local.upsertCard(card)
            if (card == stored && cloudCovers(touched(card), mine?.let(::touched))) cardIds += doc.id
        }
        for (doc in customWordDocs) {
            max = maxOf(max, doc.updatedAt)
            val remote = customWordFrom(uid, doc.data) ?: continue
            val mine = local.customWord(uid, remote.id)
            if (mine == null || remote.updatedAt > mine.updatedAt) local.upsertCustomWord(remote)
            if (cloudCovers(remote.updatedAt, mine?.updatedAt)) customWordIds += remote.id
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
        for (doc in courseDocs) {
            max = maxOf(max, doc.updatedAt)
            val remote = courseFrom(uid, doc.data) ?: continue
            val mine = local.course(uid, remote.lessonId)
            if (mine == null || remote.lastAt > mine.lastAt) local.upsertCourse(remote)
            if (cloudCovers(remote.lastAt, mine?.lastAt)) courseIds += remote.lessonId
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
        if (decksAsDocuments) {
            deckDocs.forEach { max = maxOf(max, it.updatedAt) }
            pullDecks(uid, deckDocs)
        } else {
            // A backup written before 2.0: decks are still one list in meta/decks, merged as a whole like channels.
            items("decks")?.let { cloudItems ->
                val theirs = cloudItems.mapNotNull { legacyDeckFrom(uid, it) }
                    .map { d -> DeckDoc(d.deck, d.words.map { resolveWord(it) }.distinct()) }
                val mine = legacyDeckDocs(uid)
                if (theirs.map { legacyDeckDoc(it.deck, it.words) } != mine && mayTakeCloudVersion(uid, "decks", mine)) {
                    local.replaceDecks(uid, theirs.map { it.deck to it.words })
                }
            }
        }
        return Pulled(max, cardIds, dayIds, grammarIds, courseIds, customWordIds)
    }

    private suspend fun legacyDeckDocs(uid: String) = local.decks(uid).map { legacyDeckDoc(it, local.deckWordIds(it.id)) }

    /** Every deck of this phone as its cloud document, by key. */
    private suspend fun localDecks(uid: String): Map<String, Map<String, Any?>> =
        local.decks(uid).associate { it.key to deckDoc(it, local.deckWordIds(it.id)) }

    /**
     * Decks, one document each. Like a whole-document item, but per deck: the cloud's version (or its
     * deletion) replaces this phone's deck only while the deck is as this phone last uploaded or took it;
     * otherwise this phone's wins and is uploaded. A deck this phone deleted is not brought back; one it
     * never had is added, so decks made on two phones are both kept.
     */
    private suspend fun pullDecks(uid: String, docs: List<CloudDoc>) {
        if (docs.isEmpty()) return
        val hashes = prefs.deckHashes(uid).toMutableMap()
        val mine = localDecks(uid)
        for (doc in docs) {
            val key = doc.id
            val myDoc = mine[key]
            val unchangedHere = myDoc != null && hashes[key] == fingerprint(myDoc)
            if (isTombstone(doc.data)) {
                when {
                    myDoc == null -> hashes.remove(key)
                    unchangedHere -> { local.deleteDeck(uid, key); hashes.remove(key) }
                    else -> Unit // changed here since: it stays and is uploaded again
                }
                continue
            }
            val stored = deckFrom(uid, key, doc.data) ?: continue
            val theirs = DeckDoc(stored.deck, stored.words.map { resolveWord(it) }.distinct())
            val theirDoc = deckDoc(theirs.deck, theirs.words)
            val take = when {
                myDoc == null -> key !in hashes // a deck deleted here (listed, but gone) stays deleted
                theirDoc == myDoc -> { hashes[key] = fingerprint(myDoc); false }
                else -> unchangedHere
            }
            if (take) {
                local.putDeck(uid, theirs.deck, theirs.words)
                hashes[key] = fingerprint(theirDoc)
            }
        }
        prefs.saveDeckHashes(uid, hashes)
    }

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

    /**
     * Uploads what changed here. Sends at most [MAX_PUSH] documents (the free cloud plan allows 20,000
     * writes a day, and an import can change thousands of cards at once): when there are more cards, the
     * oldest go now and the rest at the next sync. Returns the push cursor to save when it was cut
     * short (the change time of the last card sent), null when everything went.
     */
    private suspend fun push(uid: String, since: Long, pulled: Pulled, decksAsDocuments: Boolean): Long? {
        val writes = mutableListOf<CloudWrite>()

        val cards = local.cardsSince(uid, since).filter { needsUpload(it, since) && cardId(it) !in pulled.cardIds }
            .sortedBy(::touched)
        val sinceDay = if (since == 0L) 0L else studyEpochDay(Instant.ofEpochMilli(since)) - 1
        local.days(uid).filter { it.day >= sinceDay && it.day !in pulled.dayIds }
            .forEach { writes += CloudWrite("days", it.day.toString(), dayDoc(it)) }
        local.testsSince(uid, since).filter { it.takenAt > since }
            .forEach { writes += CloudWrite("tests", testId(it), testDoc(it)) }
        local.grammarSince(uid, since).filter { it.lastAt > since && it.grammarId !in pulled.grammarIds }
            .forEach { writes += CloudWrite("grammar", it.grammarId.toString(), grammarDoc(it)) }
        local.courseSince(uid, since).filter { it.lastAt > since && it.lessonId !in pulled.courseIds }
            .forEach { writes += CloudWrite("course", it.lessonId.toString(), courseDoc(it)) }

        // Small whole-document items: each is written only when it changed here since it was last uploaded
        // or taken (or on a full push).
        val channels = local.channels(uid).map(::channelDoc)
        val settings = prefs.export(uid)
        val parts = mapOf("channels" to channels, "settings" to settings)
        val changed = parts.filter { (part, value) -> since == 0L || prefs.uploadedHash(uid, part) != fingerprint(value) }.keys
        if ("channels" in changed) writes += CloudWrite(META_COLLECTION, "channels", mapOf("items" to channels))
        if ("settings" in changed && settings.isNotEmpty()) writes += CloudWrite(META_COLLECTION, "profile", mapOf("prefs" to settings))

        // Decks: one document each, written when it changed here; a deleted deck leaves a tombstone. The
        // first push to a backup still in format 1 moves the decks out of meta/decks and raises the format,
        // so a FluRai older than 2.0 stops syncing instead of losing them.
        val decks = localDecks(uid)
        val oldHashes = prefs.deckHashes(uid)
        val moving = !decksAsDocuments
        decks.filter { (key, doc) -> moving || since == 0L || oldHashes[key] != fingerprint(doc) }
            .forEach { (key, doc) -> writes += CloudWrite(DECK_COLLECTION, key, doc) }
        (oldHashes.keys - decks.keys).forEach { writes += CloudWrite(DECK_COLLECTION, it, DECK_TOMBSTONE) }
        if (moving) {
            writes += CloudWrite(META_COLLECTION, "decks", mapOf("items" to emptyList<Any>(), "movedTo" to DECK_COLLECTION))
            writes += CloudWrite(META_COLLECTION, SCHEMA_DOC, mapOf("minVersion" to SYNC_FORMAT))
        }

        // Cards and the learner's own words (an import makes thousands of each) fill what is left of the
        // limit, oldest change first. Ones changed in the same millisecond as the last one sent go too, so
        // the cursor (that millisecond) never skips any.
        val queued = (
            cards.map { touched(it) to CloudWrite("cards", cardId(it), cardDoc(it)) } +
                local.customWordsSince(uid, since).filter { it.updatedAt > since && it.id !in pulled.customWordIds }
                    .map { it.updatedAt to CloudWrite(CUSTOM_WORD_COLLECTION, it.id.toString(), customWordDoc(it)) }
            ).sortedBy { it.first }
        val room = (MAX_PUSH - writes.size).coerceAtLeast(0)
        val sent = if (queued.size <= room) queued else {
            val boundary = queued.getOrNull(room - 1)?.first
            if (boundary == null) emptyList() else queued.takeWhile { it.first <= boundary }
        }
        sent.forEach { writes += it.second }
        val left = queued.size - sent.size

        if (writes.isNotEmpty()) cloud.write(uid, writes)
        changed.forEach { prefs.saveUploadedHash(uid, it, fingerprint(parts.getValue(it))) }
        prefs.saveDeckHashes(uid, decks.mapValues { fingerprint(it.value) })
        prefs.savePushLeft(uid, left)
        return if (left == 0) null else sent.lastOrNull()?.first ?: since
    }

    companion object {
        /** The most documents one sync uploads; the rest wait for the next sync. */
        const val MAX_PUSH = 2_000
    }
}
