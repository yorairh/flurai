package com.flurai.app.core.reading

import org.junit.Assert.assertEquals
import org.junit.Test

class FuriganaTest {
    private fun parts(surface: String, reading: String) = rubyParts(surface, reading).map { it.text to it.ruby }

    @Test
    fun aReadingThatRepeatsItsOkuriganaIsStillAligned() {
        // かしだし: the し after 貸出 is the last one, not the first
        assertEquals(listOf("貸出" to "かしだ", "し" to null), parts("貸出し", "かしだし"))
        assertEquals(listOf("取" to "と", "り" to null, "出" to "だ", "す" to null), parts("取り出す", "とりだす"))
    }

    @Test
    fun markupIsReadIntoParts() {
        val parsed = parseRuby("{誰|だれ}が{来|き}ました。").map { it.text to it.ruby }
        assertEquals(listOf("誰" to "だれ", "が" to null, "来" to "き", "ました。" to null), parsed)
        assertEquals(listOf("ありがとう" to null), parseRuby("ありがとう").map { it.text to it.ruby })
        assertEquals(listOf("貸出し" to "かしだし", "を" to null), parseRuby("{貸出し|かしだし}を").map { it.text to it.ruby })
    }

    @Test
    fun aBraceThatIsNotMarkupStaysText() {
        assertEquals(listOf("a{b" to null), parseRuby("a{b").map { it.text to it.ruby })
        assertEquals(listOf("{x}" to null), parseRuby("{x}").map { it.text to it.ruby })
    }

    @Test
    fun allKanjiWordGetsOneReading() {
        assertEquals(listOf("医者" to "いしゃ"), parts("医者", "いしゃ"))
    }

    @Test
    fun okuriganaGetsNoReading() {
        assertEquals(listOf("食" to "た", "べる" to null), parts("食べる", "たべる"))
    }

    @Test
    fun kanaInsideTheWordIsSkipped() {
        assertEquals(listOf("引" to "ひ", "っ" to null, "越" to "こ", "す" to null), parts("引っ越す", "ひっこす"))
        assertEquals(listOf("食" to "た", "べ" to null, "物" to "もの"), parts("食べ物", "たべもの"))
    }

    @Test
    fun prefixKanaIsSkipped() {
        assertEquals(listOf("お" to null, "金" to "かね"), parts("お金", "おかね"))
    }

    @Test
    fun kanaOnlyWordsHaveNoReading() {
        assertEquals(listOf("コート" to null), parts("コート", "こうと"))
        assertEquals(listOf("いっぱい" to null), parts("いっぱい", "いっぱい"))
    }

    @Test
    fun aReadingThatDoesNotFitFallsBackToTheWholeWord() {
        assertEquals(listOf("食べる" to "のむ"), parts("食べる", "のむ"))
    }

    @Test
    fun partsAlwaysSpellTheSurface() {
        for ((s, r) in listOf("大人" to "おとな", "一人" to "ひとり", "引っ越す" to "ひっこす", "食べる" to "たべる", "お金" to "おかね")) {
            assertEquals(s, rubyParts(s, r).joinToString("") { it.text })
        }
    }
}
