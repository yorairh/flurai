package com.flurai.app.core.sync

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.flurai.app.core.activity.StudyDayEntity
import com.flurai.app.core.auth.GUEST_UID
import com.flurai.app.core.grammar.GrammarProgressEntity
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
        val engine = SyncEngine(cloud, local, dataStore, { now }, { errors += it })

        fun sync(uid: String = UID) = runBlocking { engine.sync(uid) }.also { ok ->
            if (!ok && uid != GUEST_UID) errors.lastOrNull()?.printStackTrace()
        }
        fun setting(name: String, value: String) = runBlocking { dataStore.edit { it[stringPreferencesKey("$UID.$name")] = value } }
        fun setting(name: String) = runBlocking { dataStore.data.first()[stringPreferencesKey("$UID.$name")] }
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

        // "Add to flashcards" from a word page: creation time 0, never reviewed.
        a.now = 20_000
        a.local.cardRows += card(2, lastReview = null, createdAt = 0, reps = 0)
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
    fun deletingTheCloudCopyAndMarkingEverythingDirtyUploadsItAllAgain() {
        val a = Phone()
        a.local.cardRows += card(1)
        a.local.channelRows += CustomChannelEntity(uid = UID, name = "Ch", url = "u", level = 5, addedAt = 1)
        a.sync()

        runBlocking { a.engine.deleteRemote(UID) }
        assertTrue(cloud.ids(UID, "cards").isEmpty())
        assertTrue(cloud.ids(UID, "meta").isEmpty())

        a.now = 20_000
        a.sync() // nothing changed since the last upload, so nothing is sent
        assertTrue(cloud.ids(UID, "cards").isEmpty())

        runBlocking { a.engine.markAllDirty(UID) }
        a.now = 30_000
        a.sync()
        assertNotNull(cloudCard(1))
        assertTrue("channels" in cloud.ids(UID, "meta"))
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
