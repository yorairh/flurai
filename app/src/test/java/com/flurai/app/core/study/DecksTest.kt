package com.flurai.app.core.study

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DecksTest {
    @Test
    fun namesAreTrimmedAndSingleSpaced() {
        assertEquals("Food words", cleanDeckName("  Food    words "))
    }

    @Test
    fun emptyNamesAreRejected() {
        assertNull(cleanDeckName("   "))
        assertNull(cleanDeckName(""))
    }

    @Test
    fun longNamesAreCut() {
        assertEquals(MAX_DECK_NAME, cleanDeckName("a".repeat(100))!!.length)
    }
}
