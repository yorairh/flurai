package com.flurai.app.ui.progress

import com.flurai.app.core.content.WordEntity
import com.flurai.app.core.knowledge.Knowledge
import com.flurai.app.core.srs.SrsState
import com.flurai.app.core.study.CardEntity
import com.flurai.app.core.study.CardKind
import com.flurai.app.core.study.ItemType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WordBrowserTest {
    private val word = WordEntity(
        id = 1, expression = "水", reading = "みず", otherForms = null, meanings = "water",
        jlpt = 5, genkiLesson = null, jmdictId = null, partsOfSpeech = null,
    )

    private fun row(knowledge: Knowledge, suspended: Boolean = false) = BrowserRow(
        word = word,
        knowledge = knowledge,
        card = CardEntity(
            uid = "u", itemType = ItemType.Word, itemId = 1, kind = CardKind.Recognition,
            state = SrsState.Review.value, step = null, stability = 10.0, difficulty = 5.0, due = 0, lastReview = 0,
            reps = 1, lapses = 0, suspended = suspended, buriedUntil = null, createdAt = 0,
        ),
    )

    private fun filtersOf(row: BrowserRow) = BrowserFilter.entries.filter { row.matches(it) }.toSet()

    @Test
    fun everyStateLandsUnderItsOwnFilter() {
        assertEquals(setOf(BrowserFilter.All, BrowserFilter.Learning), filtersOf(row(Knowledge.Learning)))
        assertEquals(setOf(BrowserFilter.All, BrowserFilter.Known), filtersOf(row(Knowledge.Known)))
        assertEquals(setOf(BrowserFilter.All, BrowserFilter.Mature), filtersOf(row(Knowledge.Mature)))
    }

    @Test
    fun wordsAddedButNotAnsweredYetCountAsLearning() {
        assertTrue(row(Knowledge.Unseen).matches(BrowserFilter.Learning))
    }

    @Test
    fun suspendedWordsOnlyShowUnderSuspendedAndAll() {
        assertEquals(setOf(BrowserFilter.All, BrowserFilter.Suspended), filtersOf(row(Knowledge.Mature, suspended = true)))
        assertFalse(row(Knowledge.Learning, suspended = true).matches(BrowserFilter.Learning))
    }

    @Test
    fun labelsAndEmptyMessages() {
        assertEquals("Forgotten once", forgottenLabel(1))
        assertEquals("Forgotten 3 times", forgottenLabel(3))
        assertTrue(emptyMessage(BrowserFilter.All, allCount = 0).contains("Flashcards"))
        assertEquals("No suspended words.", emptyMessage(BrowserFilter.Suspended, allCount = 4))
        assertEquals("No mature words here yet.", emptyMessage(BrowserFilter.Mature, allCount = 4))
    }
}
