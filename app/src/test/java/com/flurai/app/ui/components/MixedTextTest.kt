package com.flurai.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MixedTextTest {
    private fun texts(markup: String) = mixedCells(markup).map { it.text }

    @Test
    fun englishWordsStayWholeAndKeepTheirSpaces() {
        assertEquals(listOf("A ", "verb ", "ends"), texts("A verb ends"))
    }

    @Test
    fun japaneseIsOneCellPerCharacterAndAKanjiWordIsOneCell() {
        val cells = mixedCells("{落|お}とす")
        assertEquals(listOf("落", "と", "す"), cells.map { it.text })
        assertEquals("お", cells[0].ruby)
        assertNull(cells[1].ruby)
    }

    @Test
    fun boldMarkersSwitchBoldAndAreNotDrawn() {
        val cells = mixedCells("the **self-move** verb")
        assertEquals(listOf("the ", "self-move ", "verb"), cells.map { it.text })
        assertEquals(listOf(false, true, false), cells.map { it.bold })
    }

    @Test
    fun boldCanCoverAKanjiWord() {
        val cells = mixedCells("**{落|お}とす** is other-move")
        assertTrue(cells.take(3).all { it.bold })
        assertFalse(cells.last().bold)
    }

    @Test
    fun closingPunctuationSticksToTheCellBefore() {
        assertEquals(listOf("食", "べ", "る。"), texts("{食|た}べる。"))
    }

    @Test
    fun theGapOfAFillInQuestionIsJapaneseNotEnglish() {
        assertEquals(listOf("ド", "ア", "＿", "＿", "開", "く。"), texts("ドア＿＿{開|あ}く。"))
    }

    @Test
    fun plainOfHidesReadingsAndBoldMarkers() {
        assertEquals("the 落とす verb", plainOf("the **{落|お}とす** verb"))
    }
}
