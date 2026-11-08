package com.flurai.app.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JlptLevelTest {
    @Test
    fun nextGoesFromEasierToHarder() {
        assertEquals(JlptLevel.N5, JlptLevel.Beginner.next())
        assertEquals(JlptLevel.N4, JlptLevel.N5.next())
        assertEquals(JlptLevel.N1, JlptLevel.N2.next())
        assertNull(JlptLevel.N1.next())
    }

    @Test
    fun isAtLeastComparesDifficultyNotTheNumber() {
        assertTrue(JlptLevel.N3.isAtLeast(JlptLevel.N5))
        assertTrue(JlptLevel.N3.isAtLeast(JlptLevel.N3))
        assertFalse(JlptLevel.N5.isAtLeast(JlptLevel.N4))
    }

    @Test
    fun jlptNumbersMapBothWays() {
        assertNull(JlptLevel.Beginner.jlpt)
        assertEquals(5, JlptLevel.N5.jlpt)
        assertEquals(1, JlptLevel.N1.jlpt)
        assertEquals(JlptLevel.N3, JlptLevel.ofJlpt(3))
        assertNull(JlptLevel.ofJlpt(0))
        assertEquals(JlptLevel.entries.drop(1), (5 downTo 1).map { JlptLevel.ofJlpt(it) })
    }
}
