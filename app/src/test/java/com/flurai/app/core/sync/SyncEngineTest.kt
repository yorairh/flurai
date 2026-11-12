package com.flurai.app.core.sync

import com.flurai.app.core.prefs.InMemoryPrefs
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.flurai.app.core.activity.StudyDayEntity
import com.flurai.app.core.auth.GUEST_UID
import com.flurai.app.core.grammar.GrammarProgressEntity
import com.flurai.app.core.prefs.UserPreferences
import com.flurai.app.core.quiz.TestResultEntity
import com.flurai.app.core.quiz.TestType
import com.flurai.app.core.study.CardEntity
import com.flurai.app.core.study.CardKind
import com.flurai.app.core.study.DeckEntity
import com.flurai.app.core.study.ItemType
import com.flurai.app.core.video.CustomChannelEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole pull-merge-push against an in-memory cloud: each test is a story of one or two phones
 * of one account syncing, written the way the learner would live it.
 */
class SyncEngineTest {
    private val cloud = InMemoryCloud()
    private val errors = mutableListOf<Throwable>()

    /** One phone: its own database, settings file and clock, sharing the test's cloud. */
    private inner class Phone {
        val local = InMemoryLocal()
        val dataStore = InMemoryPrefs()
        var now = 10_000L
        val engine = SyncEngine(cloud, local, UserPreferences(dataStore), { now }, { errors += it })

        fun sync(uid: String = UID) = runBlocking { engine.sync(uid) }.also { ok ->
            if (!ok && uid != GUEST_UID) errors.lastOrNull()?.printStackTrace()
        }
        fun setting(name: String, value: String) = runBlocking { dataStore.edit { it[stringPreferencesKey("$UID.$name")] = value } }
        fun setting(name: String) = runBlocking { dataStore.data.first()[stringPreferencesKey("$UID.$name")] }

        fun addDeck(name: String, vararg words: Int) {
            val id = (local.deckRows.maxOfOrNull { it.id } ?: 0) + 1
            local.deckRows += DeckEntity(id = id, uid = UID, name = name, createdAt = id.toLong())
            local.deckWords[id] = words.toList()
        }

        fun renameDeck(from: String, to: String) {
            val i = local.deckRows.indexOfFirst { it.name == from }
            local.deckRows[i] = local.deckRows[i].copy(name = to)
        }

        fun deleteDeck(name: String) {
            local.deckRows.removeAll { it.name == name }
        }

        fun deckNames() = local.deckRows.map { it.name }

        /** A later sync: this phone's clock and the cloud's both moved on. */
        fun syncLater(): Boolean {
            now += 10_000
            cloud.serverTime += 4_000
            return sync()
        }
    }

    private fun card(id: Int, lastReview: Long? = 500, createdAt: Long = 100, reps: Int = 1, kind: CardKind = CardKind.Recognition) = CardEntity(
        uid = UID, itemType = ItemType.Word, itemId = id, kind = kind, state = 2, step = null, stability = 5.0, difficulty = 4.0,
        due = 99_000, lastReview = lastReview, reps = reps, lapses = 0, suspended = false, buriedUntil = null, createdAt = createdAt,
    )

    private fun cloudCard(id: Int) = cloud.doc(UID, "cards", "Word-$id-Recognition")

    // -- restore ---------------------------------------------------------------------------------

    @Test
    fun everythingBackedUpComesBackOnANewPhone() {
        val a = Phone()
        a.local.cardRows += listOf(card(1), card(2, kind = CardKind.Production))
        a.local.dayRows += StudyDayEntity(UID, 20_000, 600, 40)
        a.local.testRows += TestResultEntity(uid = UID, type = TestType.Quick, level = null, correct = 7, total = 10, takenAt = 7_000)
        a.local.grammarRows += GrammarProgressEntity(UID, 501, 3, 1, true, 8_000)
        a.local.channelRows += CustomChannelEntity(uid = UID, name = "Ch", url = "https://x", level = 4, addedAt = 5)
        a.local.deckRows += DeckEntity(id = 9, uid = UID, name = "Trip", createdAt = 6)
        a.local.deckWords[9] = listOf(11, 12)
        a.setting("level", "N4")
        assertTrue(a.sync())

        val b = Phone()
        assertTrue(b.sync())

        assertEquals(a.local.cardRows.toSet(), b.local.cardRows.toSet())
        assertEquals(a.local.dayRows, b.local.dayRows)
        assertEquals(a.local.testRows, b.local.testRows)
        assertEquals(a.local.grammarRows, b.local.grammarRows)
        assertEquals(listOf("Ch"), b.local.channelRows.map { it.name })
        assertEquals(listOf("Trip"), b.local.deckRows.map { it.name })
        assertEquals(listOf(11, 12), b.local.deckWords[b.local.deckRows.single().id])
        assertEquals("N4", b.setting("level"))
    }

    @Test
    fun settingsAndListsAreRestoredOnlyOntoAnEmptyPhone() {
        val a = Phone()
        a.local.deckRows += DeckEntity(id = 1, uid = UID, name = "A's deck", createdAt = 1)
        a.setting("level", "N4")
        a.sync()

        val b = Phone()
        b.local.deckRows += DeckEntity(id = 1, uid = UID, name = "B's deck", createdAt = 1)
        b.setting("level", "N2")
        b.sync()

        assertEquals(listOf("B's deck"), b.local.deckRows.map { it.name })
        assertEquals("N2", b.setting("level"))
    }

    // -- a review made after a sync must reach the cloud -------------------------------------------

    @Test
    fun aReviewMadeBetweenTwoSyncsIsUploaded() {
        val a = Phone()
        a.local.cardRows += card(1, lastReview = 500)
        a.sync() // uploads card 1 as reviewed at 500
        assertEquals(500L, cloudCard(1)!!["lastReview"])

        a.now = 20_000
        a.local.cardRows[0] = card(1, lastReview = 20_500, reps = 2)
        a.now = 30_000
        cloud.serverTime = 5_000
        a.sync() // the pull brings back A's own upload; the newer local review must still go up

        assertEquals(20_500L, cloudCard(1)!!["lastReview"])
        assertEquals(2L, cloudCard(1)!!["reps"])
    }

    @Test
    fun aLessonMarkedLearnedReachesTheOtherPhoneAndKeepsLaterAnswers() {
        val a = Phone()
        val b = Phone()
        a.local.grammarRows += GrammarProgressEntity(UID, 526, 0, 0, false, 1_000) // learned, not practiced yet
        a.sync()
        b.sync()
        assertEquals(a.local.grammarRows, b.local.grammarRows)

        // Practiced on B afterwards: the answers win over A's bare "learned" row.
        b.now = 20_000
        b.local.grammarRows[0] = GrammarProgressEntity(UID, 526, 1, 0, true, 21_000)
        b.now = 30_000
        b.sync()
        a.now = 40_000
        a.sync()
        assertEquals(1, a.local.grammarRows.single().correct)
    }

    @Test
    fun grammarProgressMadeBetweenTwoSyncsIsUploadedToo() {
        val a = Phone()
        a.local.grammarRows += GrammarProgressEntity(UID, 7, 1, 0, true, 1_000)
        a.sync()

        a.now = 20_000
        a.local.grammarRows[0] = GrammarProgressEntity(UID, 7, 2, 0, true, 21_000)
        a.now = 30_000
        a.sync()

        assertEquals(2L, cloud.doc(UID, "grammar", "7")!!["correct"])
    }

    @Test
    fun aWordAddedByHandIsUploadedBeforeItsFirstReview() {
        val a = Phone()
        a.local.cardRows += card(1)
        a.sync()

        // "Add to flashcards" from a word page: a card that was never reviewed.
        a.now = 20_000
        a.local.cardRows += card(2, lastReview = null, createdAt = 20_000, reps = 0)
        a.now = 30_000
        a.sync()

        assertNotNull(cloudCard(2))
        val b = Phone()
        b.sync()
        assertTrue(b.local.cardRows.any { it.itemId == 2 })
    }

    // -- two phones ------------------------------------------------------------------------------

    @Test
    fun aReviewOnOnePhoneReachesTheOther() {
        val a = Phone()
        a.local.cardRows += card(1, lastReview = 500)
        a.sync()

        val b = Phone()
        b.sync()
        b.now = 20_000
        b.local.cardRows[0] = card(1, lastReview = 25_000, reps = 2)
        b.now = 30_000
        cloud.serverTime = 40_000
        b.sync()

        a.now = 50_000
        cloud.serverTime = 60_000
        a.sync()
        assertEquals(25_000L, a.local.cardRows.single().lastReview)
    }

    @Test
    fun anOlderCloudCopyNeverOverwritesANewerLocalOne() {
        val a = Phone()
        a.local.cardRows += card(1, lastReview = 900)
        val b = Phone()
        b.local.cardRows += card(1, lastReview = 300)
        b.sync() // the cloud now holds the older review
        a.sync()

        assertEquals(900L, a.local.cardRows.single().lastReview)
        assertEquals(900L, cloudCard(1)!!["lastReview"])
    }

    @Test
    fun studyTimeOnTwoPhonesTheSameDayKeepsTheLargerTotal() {
        val a = Phone()
        a.local.dayRows += StudyDayEntity(UID, 20_000, 300, 10)
        val b = Phone()
        b.local.dayRows += StudyDayEntity(UID, 20_000, 600, 4)
        a.sync()
        b.sync()
        a.now = 20_000
        cloud.serverTime = 30_000
        a.sync()

        assertEquals(StudyDayEntity(UID, 20_000, 600, 10), a.local.dayRows.single())
        assertEquals(StudyDayEntity(UID, 20_000, 600, 10), b.local.dayRows.single())
        assertEquals(600L, cloud.doc(UID, "days", "20000")!!["seconds"])
    }

    @Test
    fun aTestResultIsNeverDuplicated() {
        val a = Phone()
        a.local.testRows += TestResultEntity(uid = UID, type = TestType.Checkpoint, level = 5, correct = 18, total = 20, takenAt = 7_000)
        a.sync()
        val b = Phone()
        b.sync()
        b.now = 20_000
        cloud.serverTime = 30_000
        b.sync()
        a.sync()

        assertEquals(1, b.local.testRows.size)
        assertEquals(1, a.local.testRows.size)
    }

    // -- failures and retries ----------------------------------------------------------------------

    @Test
    fun aDeniedSyncRefreshesTheSignInAndRetriesOnce() {
        val a = Phone()
        a.local.cardRows += card(1)
        cloud.failNext(CloudException.Reason.NotAllowed)

        assertTrue(a.sync())
        assertEquals(1, cloud.refreshCount)
        assertNotNull(cloudCard(1))
    }

    @Test
    fun aSyncThatKeepsBeingDeniedGivesUpWithAReason() {
        val a = Phone()
        cloud.failAlways(CloudException.Reason.NotAllowed)

        assertFalse(a.sync())
        assertEquals(1, cloud.refreshCount)
        assertTrue(a.engine.status.value.error!!.contains("not allowed"))
    }

    @Test
    fun anOfflineSyncKeepsLocalDataAndTheNextOneUploadsEverything() {
        val a = Phone()
        a.local.cardRows += card(1)
        cloud.failAlways(CloudException.Reason.Offline)

        assertFalse(a.sync())
        assertTrue(a.engine.status.value.error!!.contains("no connection"))
        assertEquals(1, a.local.cardRows.size)
        assertEquals(1, errors.size)

        cloud.heal()
        assertTrue(a.sync()) // the failed run must not have moved the cursors past the card
        assertNotNull(cloudCard(1))
        assertNull(a.engine.status.value.error)
    }

    @Test
    fun theGuestNeverSyncs() {
        val a = Phone()
        a.local.cardRows += card(1).copy(uid = GUEST_UID)
        assertFalse(a.sync(GUEST_UID))
        assertTrue(cloud.ids(GUEST_UID, "cards").isEmpty())
    }

    // -- cloud copy deleted -----------------------------------------------------------------------

    @Test
    fun afterTheCloudCopyIsDeletedTheNextSyncUploadsItAllAgain() {
        val a = Phone()
        a.local.cardRows += card(1)
        a.local.channelRows += CustomChannelEntity(uid = UID, name = "Ch", url = "u", level = 5, addedAt = 1)
        a.sync()

        runBlocking { a.engine.deleteRemote(UID) }
        assertTrue(cloud.ids(UID, "cards").isEmpty())
        assertTrue(cloud.ids(UID, "meta").isEmpty())

        // The account lives on (say its deletion failed): nothing changed locally, but the backup must come back.
        a.now = 20_000
        a.sync()
        assertNotNull(cloudCard(1))
        assertTrue("channels" in cloud.ids(UID, "meta"))
    }

    // -- decks and channels follow changes, including deletes ---------------------------------------------

    @Test
    fun aDeckDeletedOnOnePhoneDisappearsFromTheOther() {
        val a = Phone()
        a.addDeck("Trip", 1); a.addDeck("Food", 2)
        a.sync()
        val b = Phone()
        b.sync()
        assertEquals(listOf("Trip", "Food"), b.deckNames())

        a.deleteDeck("Trip")
        a.syncLater()
        b.syncLater()

        assertEquals(listOf("Food"), b.deckNames())
        assertEquals(listOf(2), b.local.deckWords[b.local.deckRows.single().id])
    }

    @Test
    fun decksDeletedEverywhereOnThisPhoneStayDeletedAndAreNotBroughtBackFromTheCloud() {
        val a = Phone()
        a.addDeck("Trip", 1); a.addDeck("Food", 2)
        a.sync()

        a.deleteDeck("Trip"); a.deleteDeck("Food")
        a.syncLater()

        assertTrue(a.deckNames().isEmpty())
        assertEquals(emptyList<Any?>(), cloud.doc(UID, "meta", "decks")!!["items"])
        val b = Phone()
        b.sync()
        assertTrue(b.deckNames().isEmpty())
    }

    @Test
    fun aDeckAddedOrRenamedOnOnePhoneReachesTheOther() {
        val a = Phone()
        a.addDeck("Trip", 1)
        a.sync()
        val b = Phone()
        b.sync()

        a.renameDeck("Trip", "Trip 2026"); a.addDeck("Food", 2)
        a.syncLater()
        b.syncLater()

        assertEquals(listOf("Trip 2026", "Food"), b.deckNames())
    }

    @Test
    fun aChangeMadeOnThisPhoneIsNotOverwrittenByTheCloud() {
        val a = Phone()
        a.addDeck("Trip", 1)
        a.sync()
        val b = Phone()
        b.sync()

        b.deleteDeck("Trip") // not synced yet
        a.addDeck("Food", 2)
        a.syncLater()
        b.syncLater()

        assertTrue(b.deckNames().isEmpty()) // this phone's change won and was uploaded
        assertEquals(emptyList<Any?>(), cloud.doc(UID, "meta", "decks")!!["items"])
    }

    @Test
    fun aChannelDeletedOnOnePhoneDisappearsFromTheOther() {
        val a = Phone()
        a.local.channelRows += CustomChannelEntity(uid = UID, name = "Ch", url = "https://x", level = 5, addedAt = 1)
        a.sync()
        val b = Phone()
        b.sync()
        assertEquals(listOf("Ch"), b.local.channelRows.map { it.name })

        a.local.channelRows.clear()
        a.syncLater()
        b.syncLater()

        assertTrue(b.local.channelRows.isEmpty())
    }

    @Test
    fun aPhoneThatNeverSyncedKeepsItsOwnDecksInsteadOfTakingTheClouds() {
        val a = Phone()
        a.addDeck("Cloud deck", 1)
        a.sync()
        val b = Phone()
        b.addDeck("Own deck", 2)

        b.syncLater()

        assertEquals(listOf("Own deck"), b.deckNames())
    }

    // -- the documents ---------------------------------------------------------------------------

    @Test
    fun aBackupFromAnOlderVersionStillRestores() {
        // Written by 0.22.0: the same fields, whole numbers, and updatedAt as a plain number (not seen by the engine).
        cloud.serverTime = 1_700_000_000_000
        runBlocking {
            cloud.write(
                UID,
                listOf(
                    CloudWrite("cards", "Word-5-Recognition", mapOf("itemType" to "Word", "itemId" to 5, "kind" to "Recognition", "state" to 2, "step" to null, "stability" to 3.5, "difficulty" to 5.0, "due" to 9_000, "lastReview" to 100, "reps" to 2, "lapses" to 0, "suspended" to false, "buriedUntil" to null, "createdAt" to 50)),
                    CloudWrite("days", "19000", mapOf("day" to 19_000, "seconds" to 120, "reviews" to 9)),
                ),
            )
        }
        val a = Phone()
        assertTrue(a.sync())
        assertEquals(5, a.local.cardRows.single().itemId)
        assertEquals(StudyDayEntity(UID, 19_000, 120, 9), a.local.dayRows.single())
    }

    private companion object {
        const val UID = "u"
    }
}
