package com.flurai.app.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PartOfSpeechTest {
    @Test
    fun labelsComeFromTheFirstCode() {
        assertEquals("Godan verb", partOfSpeechLabel("v5r,vt"))
        assertEquals("Ichidan verb", partOfSpeechLabel("v1,vt"))
        assertEquals("い-adjective", partOfSpeechLabel("adj-i"))
        assertEquals("Noun", partOfSpeechLabel("n,adj-no"))
    }

    @Test
    fun unknownOrMissingCodesHaveNoLabel() {
        assertNull(partOfSpeechLabel(null))
        assertNull(partOfSpeechLabel("unc"))
    }
}
