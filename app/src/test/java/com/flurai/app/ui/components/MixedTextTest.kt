package com.flurai.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MixedTextTest {
    private fun texts(markup: String) = mixedCells(markup).map { it.full }

    @Test
    fun englishWordsStayWholeAndKeepTheirSpaces() {
        assertEquals(listOf("A ", "verb ", "ends"), texts("A verb ends"))
    }

    @Test
    fun japaneseIsOneCellPerCharacterAndAKanjiWordIsOneCell() {
        val cells = mixedCells("{落|お}とす")
        assertEquals(listOf("落"), cells.map { it.text })
        assertEquals("とす", cells[0].tail)
        assertEquals("お", cells[0].ruby)
        assertEquals(listOf("食", "べ", "る"), mixedCells("食べる").map { it.text })
        assertNull(mixedCells("食べる")[1].ruby)
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
        assertTrue(cells.take(1).all { it.bold })
        assertEquals("とす", cells[0].tail)
        assertFalse(cells.last().bold)
    }

    @Test
    fun closingPunctuationSticksToTheCellBefore() {
        assertEquals(listOf("食べる。"), texts("{食|た}べる。"))
    }

    @Test
    fun theGapOfAFillInQuestionIsJapaneseNotEnglish() {
        assertEquals(listOf("ドア", "＿", "＿", "開く。"), texts("ドア＿＿{開|あ}く。"))
    }

    @Test
    fun plainOfHidesReadingsAndBoldMarkers() {
        assertEquals("the 落とす verb", plainOf("the **{落|お}とす** verb"))
    }

    @Test
    fun aKanjiWordKeepsItsOkuriganaButNotMoreThanTwoKana() {
        assertEquals(listOf("投げる。"), texts("{投|な}げる。"))
        // 歩いている: いて stays with 歩, the rest may break away
        assertEquals(listOf("歩いて", "い", "る。"), texts("{歩|ある}いている。"))
    }

    @Test
    fun aKatakanaWordIsOneCell() {
        assertEquals(listOf("ボール", "を"), texts("ボールを"))
        assertEquals(listOf("ボール", "を", "投げる"), texts("ボールを{投|な}げる"))
    }

    @Test
    fun englishWrittenStraightAfterJapaneseStaysWithIt() {
        assertEquals(listOf("an ", "い-adjective ", "is"), texts("an い-adjective is"))
        assertEquals(listOf("a ", "う-row ", "sound"), texts("a う-row sound"))
    }
}
