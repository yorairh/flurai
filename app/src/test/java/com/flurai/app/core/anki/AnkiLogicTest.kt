package com.flurai.app.core.anki

import com.flurai.app.core.content.WordEntity
import com.flurai.app.core.dictionary.levelLabel
import com.flurai.app.core.srs.SrsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class AnkiLogicTest {
    // -- field text --------------------------------------------------------------------------------

    @Test
    fun fieldsLoseHtmlSoundsAndPictures() {
        assertEquals("school (building)", cleanField("school&nbsp;(building)"))
        assertEquals("学校", cleanField("<b>学校</b>"))
        assertEquals("favourite idol", cleanField("favourite idol [sound:oshi.mp3]"))
        assertEquals("a b", cleanField("a<br>b<img src=\"x.jpg\">"))
        assertEquals("<tag> & \"q\" é", cleanField("&lt;tag&gt; &amp; &quot;q&quot; &#233;"))
    }

    /**
     * Android's ICU regex engine rejects a literal "]" or "}" that is not escaped (the JVM accepts it, so
     * other tests would not notice): every one in AnkiLogic.kt must close a [class] or a {count}, or be escaped.
     */
    @Test
    fun regexesEscapeClosingBrackets() {
        val source = java.io.File("src/main/java/com/flurai/app/core/anki/AnkiLogic.kt").readText()
        val patterns = Regex("""Regex\("((?:[^"\\]|\\.)*)"\)""").findAll(source).map { it.groupValues[1].replace("\\\\", "\\") }.toList()
        assertTrue(patterns.size >= 5)
        for (p in patterns) {
            var inClass = false
            var inCount = false
            var i = 0
            while (i < p.length) {
                when (val ch = p[i]) {
                    '\\' -> i++
                    '[' -> if (!inClass) inClass = true
                    ']' -> { assertTrue("unescaped ] in $p", inClass); inClass = false }
                    '{' -> if (!inClass) inCount = true
                    '}' -> if (!inClass) { assertTrue("unescaped } in $p", inCount); inCount = false } else assertTrue("unescaped } in a class in $p", false)
                    else -> Unit.also { ch }
                }
                i++
            }
        }
    }

    @Test
    fun ankiFuriganaGivesTheWordAndItsReading() {
        assertEquals("日本語", withoutFurigana("日本[にほん] 語[ご]"))
        assertEquals("にほんご", furiganaReading("日本[にほん] 語[ご]"))
        assertEquals("食べる", withoutFurigana("食べる[たべる]"))
        assertNull(furiganaReading("食べる"))
    }

    @Test
    fun sentencesAreLongOrEndLikeASentence() {
        assertTrue(looksLikeSentence("猫が好きです。"))
        assertTrue(looksLikeSentence("本当？"))
        assertTrue(looksLikeSentence("きのうはとてもさむかったです"))
        assertFalse(looksLikeSentence("食べる"))
    }

    @Test
    fun customWordIdsAreNegativeAndStable() {
        val id = customWordId("ぴよぴよ語", "ぴよぴよご")
        assertTrue(id < 0)
        assertEquals(id, customWordId(" ぴよぴよ語", "ぴよぴよご "))
        assertTrue(id != customWordId("ぴよぴよ語", "ぴよご"))
    }

    @Test
    fun theLearnersOwnWordsAreLabelled() {
        val word = WordEntity(-5, "ぴよ", "ぴよ", null, "chirp", 0, null, null, null, null)
        assertEquals("Your word", word.levelLabel)
    }

    // -- guessing fields and templates -------------------------------------------------------------

    private fun fields(vararg names: String) = names.mapIndexed { i, n -> AnkiField(i, n) }
    private fun note(vararg values: String) = AnkiNote(1, "g", 1, values.toList())

    @Test
    fun fieldsAreFoundByName() {
        assertEquals(FieldRoles(0, 1, 2), guessFieldRoles(fields("Expression", "Reading", "Meaning", "Audio"), emptyList()))
        // Core decks: "Vocabulary-Kana" is the reading even though it says "vocabulary".
        assertEquals(FieldRoles(0, 1, 2), guessFieldRoles(fields("Vocabulary-Kanji", "Vocabulary-Kana", "Vocabulary-English", "Vocabulary-Audio"), emptyList()))
    }

    @Test
    fun fieldsWithUnhelpfulNamesAreFoundByWhatTheyHold() {
        val samples = listOf(note("cat", "ねこ", "猫"), note("dog", "いぬ", "犬"))
        assertEquals(FieldRoles(2, 1, 0), guessFieldRoles(fields("A", "B", "C"), samples))
    }

    @Test
    fun templatesAreGuessedFromWhatTheirFrontShows() {
        val f = fields("Expression", "Reading", "Meaning", "Audio")
        val roles = FieldRoles(0, 1, 2)
        fun kind(name: String, front: String) = guessTemplateKind(AnkiTemplate(0, name, front, ""), f, roles)
        assertEquals(TemplateKind.Recognition, kind("Card 1", "{{furigana:Expression}}"))
        assertEquals(TemplateKind.Production, kind("Card 2", "<div>{{Meaning}}</div>"))
        assertEquals(TemplateKind.Listening, kind("Card 3", "{{Audio}}"))
        assertEquals(TemplateKind.Listening, kind("Card 4", "{{tts ja_JP:Reading}}"))
        assertEquals(TemplateKind.Ignore, kind("Card 5", "{{Reading}}"))
    }

    @Test
    fun aNotesWordTakesItsReadingFromTheReadingFieldOrItsFurigana() {
        val roles = FieldRoles(0, 1, 2)
        assertEquals(NoteWord("食べる", "たべる", "to eat"), noteWord(note("食べる", "食べる[たべる]", "to eat"), roles))
        assertEquals(NoteWord("日本語", "にほんご", "Japanese"), noteWord(note("日本[にほん] 語[ご]", "", "Japanese"), roles))
        assertEquals(NoteWord("ふわふわ", "ふわふわ", "fluffy"), noteWord(note("ふわふわ", "", "fluffy"), roles))
        assertNull(noteWord(note("hello", "", "hi"), roles)) // no Japanese: left out
    }

    // -- scheduling --------------------------------------------------------------------------------

    private val created = 1_700_000_000L // the collection's creation, seconds

    private fun card(type: Int, queue: Int = type, due: Long = 0, interval: Int = 0, reps: Int = 0, data: String = "") =
        AnkiCard(1, 1, 1, 0, type, queue, due, interval, reps, 0, data)

    @Test
    fun aCardNeverAnsweredComesOverAsNew() {
        assertNull(seedCard(card(type = 0), emptyList(), created))
    }

    @Test
    fun anFsrsCardKeepsItsMemoryState() {
        val seed = seedCard(card(type = 2, due = 30, interval = 12, reps = 5, data = """{"s":14.5,"d":6.2,"lrt":1702000000}"""), emptyList(), created)!!
        assertEquals(SrsState.Review, seed.srs.state)
        assertEquals(14.5, seed.srs.stability!!, 0.0)
        assertEquals(6.2, seed.srs.difficulty!!, 0.0)
        assertEquals(Instant.ofEpochSecond(created).plus(Duration.ofDays(30)), seed.srs.due)
        assertEquals(Instant.ofEpochSecond(1_702_000_000), seed.srs.lastReview)
        assertEquals(5, seed.reps)
    }

    @Test
    fun anOlderCardIsReplayedFromItsHistory() {
        val t = 1_701_000_000_000L
        val history = listOf(AnkiReview(1, t, 3, 0), AnkiReview(1, t + 600_000, 3, 0), AnkiReview(1, t + 86_400_000, 3, 1), AnkiReview(1, t + 90_000_000, 0, 4))
        val seed = seedCard(card(type = 2, due = 20, interval = 4, reps = 3), history, created)!!
        assertEquals(SrsState.Review, seed.srs.state)
        assertTrue(seed.srs.stability!! > 1.0)
        assertEquals(Instant.ofEpochMilli(t + 86_400_000), seed.srs.lastReview) // the manual change (ease 0) is not an answer
    }

    @Test
    fun aReviewCardWithoutStateOrHistoryKeepsItsInterval() {
        val seed = seedCard(card(type = 2, due = 40, interval = 10, reps = 4), emptyList(), created)!!
        assertEquals(10.0, seed.srs.stability!!, 0.0)
        assertEquals(Instant.ofEpochSecond(created).plus(Duration.ofDays(30)), seed.srs.lastReview)
    }

    @Test
    fun aSuspendedCardStaysSuspended() {
        assertTrue(seedCard(card(type = 2, queue = -1, due = 10, interval = 3, reps = 2), emptyList(), created)!!.suspended)
    }

    @Test
    fun overdueCardsAreSpreadOverTheComingWeek() {
        val now = Instant.parse("2026-11-22T10:00:00Z")
        val dues = (1..70).map { now.minus(Duration.ofDays(it.toLong())) } + now.plus(Duration.ofDays(3))
        val spread = spreadOverdue(dues, now)
        assertEquals(now.plus(Duration.ofDays(3)), spread.last()) // not overdue: untouched
        val days = spread.dropLast(1).map { Duration.between(now, it).toDays() }
        assertEquals((0L..6L).toSet(), days.toSet())
        assertEquals(10, days.count { it == 0L })
        assertEquals(0L, Duration.between(now, spread[69]).toDays()) // the most overdue comes first
    }
}
