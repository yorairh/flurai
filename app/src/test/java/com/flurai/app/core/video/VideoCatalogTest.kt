package com.flurai.app.core.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoCatalogTest {
    @Test
    fun everyLevelHasChannels() {
        (1..5).forEach { assertTrue("N$it", channelsFor(it).isNotEmpty()) }
    }

    @Test
    fun searchUrlEncodesTheQuery() {
        assertEquals("https://www.youtube.com/results?search_query=Tokini+Andy+Genki", searchUrl("Tokini Andy Genki"))
        assertTrue(searchUrl("日本語の森").startsWith("https://www.youtube.com/results?search_query=%E6%97%A5"))
    }

    @Test
    fun grammarQueryDropsTheTildeAndNotes() {
        assertEquals("たことがある Japanese grammar", grammarQuery("〜たことがある"))
        assertEquals("そうだ Japanese grammar", grammarQuery("〜そうだ (appearance)"))
        assertEquals("から Japanese grammar", grammarQuery("から"))
    }
}
