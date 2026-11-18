package com.flurai.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RubyRoomTest {

    // readings drawn at 10sp over 12sp text (a caption)
    private val scale = 10f / 12f

    @Test
    fun theNextReadingOnlyGetsWhatThePreviousOneLeftOfTheKanaBetween() {
        // 店に卵: みせ takes part of に, so たまご may only use the rest, and the two readings never meet
        val cells = listOf(RubyRoomCell("店", "みせ", "に"), RubyRoomCell("卵", "たまご", "を"))
        val rooms = rubyRooms(cells, scale)
        val miseTakes = minOf(emWidth("みせ") * scale - 1f, 1.2f) // first word: all of its extra on the right
        assertEquals(1f, rooms[0].right, 0.001f) // the whole に
        assertEquals(1f - miseTakes, rooms[1].left, 0.001f)
        assertTrue(rooms[1].left < 1f)
    }

    @Test
    fun aReadingNeverReachesIntoAWordThatHasAReadingRightNextToIt() {
        val rooms = rubyRooms(listOf(RubyRoomCell("学", "がく"), RubyRoomCell("校", "こう")), scale)
        assertEquals(0f, rooms[0].right, 0f)
        assertEquals(0f, rooms[1].left, 0f)
    }

    @Test
    fun nextToPlainTextOnlyAReadingMayReachFreely() {
        val rooms = rubyRooms(listOf(RubyRoomCell("say ", null), RubyRoomCell("猫", "ねこ", "だ")), scale)
        assertEquals(RubyRoom.NONE, rooms[0])
        assertTrue(rooms[1].left > 100f && rooms[1].right > 100f)
    }

    @Test
    fun theFirstReadingOfATextHasNoRoomOnTheLeft() {
        assertEquals(0f, rubyRooms(listOf(RubyRoomCell("公園", "こうえん", "で")), scale)[0].left, 0f)
    }

    @Test
    fun plainCellsBetweenTwoReadingsCountAsRoom() {
        // 本 は 机: the plain は (its own cell) lies between the two words
        val rooms = rubyRooms(listOf(RubyRoomCell("本", "ほん"), RubyRoomCell("は", null), RubyRoomCell("机", "つくえ")), scale, firstAtStart = false)
        val honTakes = (emWidth("ほん") * scale - 1f) / 2
        assertEquals(1f, rooms[0].right, 0.001f)
        assertEquals(1f - honTakes, rooms[2].left, 0.001f)
    }
}
