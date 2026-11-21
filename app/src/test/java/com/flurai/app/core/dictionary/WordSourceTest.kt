package com.flurai.app.core.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WordSourceTest {
    @Test
    fun idsAtOrAboveAMillionAreDictionaryWords() {
        assertFalse(isDictionaryId(1))
        assertFalse(isDictionaryId(8_025))
        assertFalse(isDictionaryId(999_999))
        assertTrue(isDictionaryId(1_000_000))
        assertTrue(isDictionaryId(1_358_280))
    }

    @Test
    fun aDictionaryEntryBecomesAWordWithoutALevel() {
        val word = DictEntryEntity(1_234_567, "飲料", "いんりょう", null, "beverage, drink", "n", "{飲|いん}{料|りょう}", true).asWord()
        assertEquals(1_234_567, word.id)
        assertEquals(1_234_567, word.jmdictId)
        assertFalse(word.isJlpt)
        assertEquals("Not JLPT", word.levelLabel)
        assertEquals("{飲|いん}{料|りょう}", word.ruby)
    }

    @Test
    fun anExampleNeverSharesAnIdWithATatoebaSentence() {
        assertEquals(-1_234_567, DictExampleEntity(1_234_567, "飲料を買う。", "I buy a drink.", null).asSentence().id)
    }

    @Test
    fun englishSearchUsesTheQuerysWords() {
        assertEquals("to eat", ftsQuery("To eat"))
        assertEquals("rock n roll", ftsQuery("rock 'n' roll!"))
        assertEquals("", ftsQuery("食べる"))
        assertEquals("", ftsQuery("たべる"))
    }

    @Test
    fun onlyDictionaryIdsThatAreNowBuiltInAreMerged() {
        val builtIn = mapOf(2_830_001 to 8_100, 1_358_280 to 77)
        assertEquals(
            listOf(1_358_280 to 77, 2_830_001 to 8_100),
            mergeTargets(listOf(2_830_001, 1_999_999, 12, 1_358_280), builtIn),
        )
        assertEquals(emptyList<Pair<Int, Int>>(), mergeTargets(listOf(77), builtIn)) // a built-in id is never moved
    }
}
