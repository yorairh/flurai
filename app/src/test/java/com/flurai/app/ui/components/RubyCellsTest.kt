package com.flurai.app.ui.components

import com.flurai.app.core.reading.RubyPart
import com.flurai.app.core.reading.parseRuby
import org.junit.Assert.assertEquals
import org.junit.Test

class RubyCellsTest {
    private fun cells(markup: String) = rubyCells(parseRuby(markup), emptyList()).map { it.text + it.tail }

    @Test
    fun aKanjiWordKeepsItsOkuriganaAndClosingPunctuation() {
        assertEquals(listOf("私が", "ボール", "を", "投げる。"), cells("{私|わたし}がボールを{投|な}げる。"))
    }

    @Test
    fun aKatakanaWordIsOnePiece() {
        assertEquals(listOf("ボール", "を"), cells("ボールを"))
    }

    @Test
    fun plainKanaBreakBetweenCharacters() {
        assertEquals(listOf("こ", "れ", "が", "本", "だ"), rubyCells(listOf(RubyPart("これが", null), RubyPart("本", "ほん").let { RubyPart("本", null) }, RubyPart("だ", null)), emptyList()).map { it.text })
    }
}
