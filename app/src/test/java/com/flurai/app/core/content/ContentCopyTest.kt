package com.flurai.app.core.content

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentCopyTest {
    @Test fun sameStampKeepsTheInstalledCopy() = assertFalse(isContentStale("abc", "abc"))

    @Test fun newStampCopiesAgain() = assertTrue(isContentStale("abc", "def"))

    @Test fun appFromBeforeStampsCopiesOnce() = assertTrue(isContentStale(null, "abc"))

    @Test fun noShippedStampNeverDeletes() {
        assertFalse(isContentStale(null, null))
        assertFalse(isContentStale("abc", null))
    }
}
