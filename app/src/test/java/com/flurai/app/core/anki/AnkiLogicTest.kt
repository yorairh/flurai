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

    /** Core 2000: "Expression" and "Reading" hold the example sentence, the word is in "Vocabulary-Kanji". */
    @Test
    fun aSentenceDecksWordFieldsWinOverItsSentenceFields() {
        val f = fields(
            "Optimized-Voc-Index", "Vocabulary-Kanji", "Vocabulary-Furigana", "Vocabulary-Kana", "Vocabulary-English",
            "Vocabulary-Audio", "Vocabulary-Pos", "Caution", "Expression", "Reading", "Sentence-Kana", "Sentence-English",
        )
        val samples = listOf(
            note("1", "それ", "それ", "それ", "that, that one", "[sound:a.mp3]", "Pronoun", "", "それはとってもいい話だ。", "それはとってもいい 話[はなし]だ。", "それはとってもいいはなしだ。", "That's a really good story."),
            note("2", "一つ", "一[ひと]つ", "ひとつ", "one (thing)", "[sound:b.mp3]", "Noun", "", "それを一つください。", "それを 一[ひと]つください。", "それをひとつください。", "Please give me one of those."),
            note("3", "一", "一[いち]", "いち", "one", "[sound:c.mp3]", "Number", "", "一から始めましょう。", "一[いち]から 始[はじ]めましょう。", "いちからはじめましょう。", "Let's start from the beginning."),
        )
        assertEquals(FieldRoles(1, 3, 4), guessFieldRoles(f, samples))
        // A deck of sentences only still takes its sentence fields.
        val sentencesOnly = listOf(note("猫が好きです。", "ねこがすきです。", "I like cats."), note("犬がいます。", "いぬがいます。", "There is a dog."))
        assertEquals(FieldRoles(0, 1, 2), guessFieldRoles(fields("Expression", "Reading", "Meaning"), sentencesOnly))
    }

    /**
     * The field layouts of popular AnkiWeb decks (names as published, sample values written here): the guess
     * must find the word, its reading and its meaning in each.
     */
    @Test
    fun popularDeckLayoutsAreGuessed() {
        data class Layout(val deck: String, val names: List<String>, val notes: List<List<String>>, val expected: Triple<String?, String?, String?>)
        val layouts = listOf(
            Layout(
                "Core 2000 (iKnow)", listOf("Expression", "Meaning", "Reading", "Audio", "Image_URI", "iKnowID", "iKnowType"),
                listOf(
                    listOf("飛ぶ", "fly", "とぶ", "[sound:a.mp3]", "", "item:1", "item"),
                    listOf("毎朝パンを食べます。", "I eat bread every morning.", "まいあさ パン を たべます", "", "", "sentence:2", "sentence"),
                    listOf("入れる", "put in", "いれる", "", "", "item:3", "item"),
                ),
                Triple("Expression", "Reading", "Meaning"),
            ),
            Layout(
                "Kaishi 1.5k", listOf("Word", "Word Reading", "Word Meaning", "Word Furigana", "Word Audio", "Sentence", "Sentence Meaning", "Sentence Furigana", "Notes", "Pitch Accent", "Frequency"),
                listOf(
                    listOf("家", "いえ", "house", "家[いえ]", "", "ここが私の家です。", "This is my house.", "ここが 私[わたし]の 家[いえ]です。", "", "イエ", "168"),
                    listOf("食べる", "たべる", "to eat", "食[た]べる", "", "パンを食べる。", "I eat bread.", "パンを 食[た]べる。", "", "", "12"),
                ),
                Triple("Word", "Word Reading", "Word Meaning"),
            ),
            Layout(
                "Genki", listOf("japanese_kana", "kanjis", "type", "english", "kanji_meaning", "sound", "sort_id", "uid"),
                listOf(
                    listOf("ろくじ", "六時", "", "six o'clock", "Six - Time", "", "5", "Genki::L01_5"),
                    listOf("あおしんごう", "", "", "green light", "", "", "18", "Genki::L09_18"),
                    listOf("にちようび", "日曜日", "", "Sunday", "Sun - Weekday", "", "20", "Genki::L04_20"),
                ),
                Triple("kanjis", "japanese_kana", "english"),
            ),
            Layout(
                "Ankidrone", listOf("SentKanji", "SentFurigana", "SentEng", "SentAudio", "VocabKanji", "VocabFurigana", "VocabPitchPattern", "VocabDef", "VocabAudio", "Image"),
                listOf(
                    listOf("この靴は一万円です。", "この 靴[くつ]は 一万円[いちまんえん]です。", "These shoes are ten thousand yen.", "", "万", "万[まん]", "", "ten thousand", "", ""),
                    listOf("例を見てください。", "例[れい]を 見[み]てください。", "Please look at the example.", "", "例", "例[れい]", "", "example", "", ""),
                ),
                Triple("VocabKanji", "VocabFurigana", "VocabDef"),
            ),
            Layout(
                "Kodansha kanji with vocabulary", listOf("id", "Kanji", "keyword", "ON reading", "KUN reading", "main ON reading", "key vocab 1 kanji", "key vocab 1 reading", "key vocab 1 english", "key vocab 2 kanji", "key vocab 2 reading", "key vocab 2 english"),
                listOf(
                    listOf("1", "病", "ill", "ビョウ", "や.む", "ビョウ", "病院", "びょういん", "hospital", "病気", "びょうき", "illness"),
                    listOf("2", "沼", "swamp", "ショウ", "ぬま", "ショウ", "沼地", "ぬまち", "swampland", "沼", "ぬま", "swamp"),
                ),
                Triple("key vocab 1 kanji", "key vocab 1 reading", "key vocab 1 english"),
            ),
            Layout(
                "Chinese JLPT book", listOf("Expression", "假名", "声调", "中文释义", "例句", "Pronunciation", "例句翻译", "发音"),
                listOf(
                    listOf("お菓子", "おかし", "②", "[名]点心", "お菓子を食べる。", "", "", "[sound:a.mp3]"),
                    listOf("数", "かず", "①", "[名]数目", "学生の数", "", "", "[sound:b.mp3]"),
                ),
                Triple("Expression", "假名", "中文释义"),
            ),
            Layout(
                "Minna no Nihongo (Chinese)", listOf("日文", "音调核", "词性", "基本形", "中文", "音频", "是否需要缩小日文", "是否需要缩小中文"),
                listOf(
                    listOf("向[む]かいます", "⓪", "動Ⅰ", "向[む]かう", "朝着～去", "", "1", ""),
                    listOf("普通[ふつう]", "", "", "", "普通列车", "", "1", "1"),
                ),
                Triple("日文", null, "中文"),
            ),
            Layout(
                "Kana-only deck", listOf("Meaning", "Kana", "Pronounciation"),
                listOf(listOf("three things", "みっつ", "[sound:a.mp3]"), listOf("lizard", "とかげ", "[sound:b.mp3]")),
                Triple("Kana", null, "Meaning"),
            ),
            Layout(
                "JLPT N5 with example sentences", listOf("Target Word with Ruby", "English Definition (Lengthy Version)", "Japanese Example Sentence", "English Translation of Sentence", "Target Japanese Word", "Target Word Furigana", "Simple Definition", "Audio"),
                listOf(
                    listOf("九[ここの]つ", "nine; nine years old (of a child)", "九[ここの]つの 子供[こども]。", "A child of nine.", "九つ", "ここのつ", "nine", ""),
                    listOf("漢字[かんじ]", "a Chinese character used in Japanese writing", "漢字[かんじ]を 書[か]く。", "I write kanji.", "漢字", "かんじ", "kanji", ""),
                ),
                Triple("Target Word with Ruby", "Target Word Furigana", "Simple Definition"),
            ),
            Layout(
                "A kanji deck storing data", listOf("Kanji", "Onyomi", "Kunyomi", "English", "Examples", "Vocab"),
                listOf(
                    listOf("医", "イ", "い.やす", "doctor, medicine", "", "[\"医療\",\"いりょう\",\"medical care\"],[\"医者\",\"いしゃ\",\"doctor\"]"),
                    listOf("盤", "バン", "", "tray, board", "", "[\"基盤\",\"きばん\",\"foundation\"],[\"円盤\",\"えんばん\",\"disc\"]"),
                ),
                Triple("Kanji", null, "English"),
            ),
            Layout(
                "Kanji deck with radicals", listOf("Kanji", "Onyomi", "Kunyomi", "English", "Radical Reading", "Components"),
                listOf(
                    listOf("医", "イ", "い.やす", "doctor, medicine", "とり・とりへん", "匚 矢"),
                    listOf("戲", "ギ", "たわむ.れる", "play, frolic", "ほこ・ほこづくり", "戈 虛"),
                ),
                Triple("Kanji", null, "English"),
            ),
        )
        for (l in layouts) {
            val f = fields(*l.names.toTypedArray())
            val roles = guessFieldRoles(f, l.notes.map { note(*it.toTypedArray()) })
            fun name(o: Int?) = o?.let { l.names[it] }
            assertEquals(l.deck, l.expected, Triple(name(roles.expression), name(roles.reading), name(roles.meaning)))
        }
    }

    @Test
    fun wordsAreAlsoLookedUpInTheirDictionaryForms() {
        fun forms(e: String, r: String) = matchVariants(NoteWord(e, r, "")).map { it.expression to it.reading }
        assertTrue(("生る" to "なる") in forms("なる(生る)", "なる"))
        assertTrue(("ごちそう" to "ごちそう") in forms("ごちそうする", "ごちそうする"))
        assertTrue(("と申します" to "ともうします") in forms("〜と申します", "〜ともうします"))
        assertTrue(("向かう" to "むかう") in forms("向かいます", "むかいます"))
        assertTrue(("食べる" to "たべる") in forms("食べます", "たべます"))
        assertTrue(("勉強する" to "べんきょうする") in forms("勉強します", "べんきょうします"))
        assertTrue(("来る" to "くる") in forms("来ます", "きます"))
        assertEquals(listOf("猫が好きです。" to ""), forms("猫が好きです。", "")) // a sentence only as it is
    }

    @Test
    fun aKanaWordWithAnEmptyKanjiFieldIsItsReading() {
        val roles = FieldRoles(1, 0, 2)
        assertEquals(NoteWord("あおしんごう", "あおしんごう", "green light"), noteWord(note("あおしんごう", "", "green light"), roles))
        assertEquals(NoteWord("肩", "かた", "shoulder"), noteWord(note("", "[肩[かた]]", "shoulder"), FieldRoles(1, null, 2)))
    }

    @Test
    fun furiganaWrittenInNestedBracketsIsRead() {
        val roles = FieldRoles(0, null, 1)
        assertEquals(NoteWord("上司", "じょうし", "boss"), noteWord(note("[上[じょう]][司[し]]", "boss"), roles))
        assertEquals(NoteWord("乾く", "かわく", "dry"), noteWord(note("[乾[かわ]]く", "dry"), roles))
    }

    @Test
    fun adjectivesAdverbsAndListedReadingsAreAlsoLookedUpWithoutTheirEnding() {
        val forms = matchVariants(NoteWord("多大な", "ただいな", "")).map { it.expression to it.reading }
        assertTrue(("多大" to "ただい") in forms)
        assertTrue(("事前" to "じぜん") in matchVariants(NoteWord("事前に", "じぜんに", "")).map { it.expression to it.reading })
        assertTrue(("何" to "なに") in matchVariants(NoteWord("何", "なに・なん", "")).map { it.expression to it.reading })
        // A kana word never loses its ending.
        assertEquals(listOf(NoteWord("だから", "だから", "")), matchVariants(NoteWord("だから", "だから", "")))
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
