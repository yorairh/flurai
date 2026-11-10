package com.flurai.app.core.sync

import com.flurai.app.core.activity.StudyDayEntity
import com.flurai.app.core.study.CardEntity
import com.flurai.app.core.study.CardKind
import com.flurai.app.core.study.ItemType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class SyncTest {
    private val card = CardEntity(
        uid = "u", itemType = ItemType.Word, itemId = 42, kind = CardKind.Recognition, state = 2, step = null,
        stability = 12.5, difficulty = 5.0, due = 1_000, lastReview = 500, reps = 3, lapses = 1,
        suspended = false, buriedUntil = null, createdAt = 100,
    )

    @Test
    fun cardIdIsStablePerItemAndKind() {
        assertEquals("Word-42-Recognition", cardId(card))
        assertEquals("Word-42-Production", cardId(card.copy(kind = CardKind.Production)))
    }

    @Test
    fun touchedIsTheLatestOfReviewAndCreation() {
        assertEquals(500, touched(card))
        assertEquals(100, touched(card.copy(lastReview = null)))
        assertEquals(900, touched(card.copy(lastReview = 50, createdAt = 900)))
    }

    @Test
    fun cardSurvivesAFirestoreRoundTripWithNumbersAsLongs() {
        // Firestore returns every whole number as Long and every fraction as Double.
        val data: Map<String, Any?> = mapOf(
            "itemType" to "Word", "itemId" to 42L, "kind" to "Recognition", "state" to 2L, "step" to null,
            "stability" to 12.5, "difficulty" to 5.0, "due" to 1_000L, "lastReview" to 500L, "reps" to 3L,
            "lapses" to 1L, "suspended" to false, "buriedUntil" to null, "createdAt" to 100L,
        )
        assertEquals(card, cardFrom("u", data))
    }

    @Test
    fun daysMergeToTheLargerTotals() {
        val remote = StudyDayEntity("u", 10, seconds = 300, reviews = 5)
        assertEquals(remote, mergeDay(null, remote))
        assertEquals(StudyDayEntity("u", 10, 600, 5), mergeDay(StudyDayEntity("u", 10, 600, 2), remote))
        // Only when the cloud copy already holds everything can the upload be skipped.
        assertEquals(remote, mergeDay(StudyDayEntity("u", 10, 100, 1), remote))
    }

    @Test
    fun updatedAtReadsServerTimestampsAndOldPlainNumbers() {
        assertEquals(5_000L, updatedAtMillis(com.google.firebase.Timestamp(java.util.Date(5_000))))
        assertEquals(1_700_000_000_000L, updatedAtMillis(1_700_000_000_000L)) // backups from before 0.23.0
        assertEquals(0L, updatedAtMillis(null)) // a write still waiting for the server
    }

    @Test
    fun onlyRowsTheCloudAlreadyHoldsAreLeftOutOfTheUpload() {
        assertTrue(cloudCovers(remote = 500, local = null))
        assertTrue(cloudCovers(remote = 500, local = 500)) // this phone's own last upload coming back
        assertTrue(cloudCovers(remote = 900, local = 500))
        assertFalse(cloudCovers(remote = 500, local = 900)) // reviewed here since: must be uploaded
    }

    @Test
    fun brokenCardDocumentsAreSkipped() {
        assertNull(cardFrom("u", mapOf("itemType" to "Nope", "kind" to "Recognition", "itemId" to 1L)))
        assertNull(cardFrom("u", mapOf("itemType" to "Word", "kind" to "Recognition")))
    }
}
