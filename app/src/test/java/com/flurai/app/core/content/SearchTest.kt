package com.flurai.app.core.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchTest {
    private fun word(id: Int, expression: String, reading: String, meanings: String, jlpt: Int = 5) = WordEntity(
        id = id, expression = expression, reading = reading, otherForms = null, meanings = meanings,
        jlpt = jlpt, genkiLesson = null, jmdictId = null, partsOfSpeech = null,
    )

    private fun kanji(literal: String, meanings: String, on: String, kun: String, jlpt: Int = 5) = KanjiEntity(
        literal = literal, jlpt = jlpt, meanings = meanings, onyomi = on, kunyomi = kun, strokeCount = 3, grade = 1, frequency = 10,
    )

    // -- romaji ------------------------------------------------------------------------------------------

    @Test
    fun romajiBecomesHiragana() {
        assertEquals("たべる", Romaji.toHiragana("taberu"))
        assertEquals("にほんご", Romaji.toHiragana("nihongo"))
        assertEquals("がっこう", Romaji.toHiragana("gakkou"))
        assertEquals("しんぶん", Romaji.toHiragana("shinbun"))
        assertEquals("きょう", Romaji.toHiragana("kyou"))
        assertEquals("こんにちは", Romaji.toHiragana("konnichiha"))
        assertEquals("さんにん", Romaji.toHiragana("sannin"))
        assertEquals("ほん", Romaji.toHiragana("hon"))
        assertEquals("ちゃ", Romaji.toHiragana("cha"))
        assertEquals("まっちゃ", Romaji.toHiragana("matcha"))
        assertEquals("とうきょう", Romaji.toHiragana("tōkyō")) // a macron gives the long vowel
        assertEquals("おう", Romaji.toHiragana("ō"))
    }

    @Test
    fun keyboardSpellingsWork() {
        assertEquals("しち", Romaji.toHiragana("siti"))
        assertEquals("じかん", Romaji.toHiragana("zikan"))
        assertEquals("ふじ", Romaji.toHiragana("huji"))
        assertEquals("しゃしん", Romaji.toHiragana("syasin"))
        assertEquals("ほんや", Romaji.toHiragana("hon'ya"))
    }

    @Test
    fun anythingThatIsNotRomajiGivesNull() {
        assertNull(Romaji.toHiragana("eat"))   // a lone t
        assertNull(Romaji.toHiragana("cat"))
        assertNull(Romaji.toHiragana("食べる"))
        assertNull(Romaji.toHiragana("two words"))
        assertNull(Romaji.toHiragana(""))
    }

    @Test
    fun katakanaFollowsHiragana() {
        assertEquals("ダイ", Romaji.toKatakana("だい"))
        assertEquals("ガッコウ", Romaji.toKatakana("がっこう"))
    }

    // -- words -------------------------------------------------------------------------------------------

    private val eat = word(1, "食べる", "たべる", "to eat")
    private val weather = word(2, "天気", "てんき", "weather; the weather")
    private val theater = word(3, "映画館", "えいがかん", "movie theater, cinema")
    private val cat = word(4, "猫", "ねこ", "cat")
    private val vacation = word(5, "夏休み", "なつやすみ", "summer vacation")
    private val meal = word(6, "食事", "しょくじ", "meal; to have a meal", jlpt = 4)
    private val all = listOf(weather, theater, eat, cat, vacation, meal)

    @Test
    fun eatFindsEatBeforeWordsThatMerelyContainTheLetters() {
        val result = rankWords(all, SearchQuery.of("eat"))
        assertEquals(1, result.first().id) // 食べる, not 天気 (w-eat-her) or 映画館 (th-eat-er)
    }

    @Test
    fun theWholeWordBeatsTheSubstring() {
        val result = rankWords(all, SearchQuery.of("cat"))
        assertEquals(4, result.first().id)
        // 夏休み has "vacation" (va-cat-ion): only a weak match, after the real cat
        assertEquals(listOf(4, 5), result.map { it.id })
    }

    @Test
    fun romajiFindsTheJapaneseWord() {
        assertEquals(1, rankWords(all, SearchQuery.of("taberu")).first().id)
        assertEquals(4, rankWords(all, SearchQuery.of("neko")).first().id)
    }

    @Test
    fun theWordItselfBeatsAMeaningThatMentionsIt() {
        val query = SearchQuery.of("食べる")
        assertEquals(0, rankWord(eat, query))
        assertNull(rankWord(cat, query))
    }

    @Test
    fun startOfAWordBeatsTheMiddleAndEasierLevelsComeFirstInATie() {
        val q = SearchQuery.of("しょく")
        assertEquals(2, rankWord(meal, q))
        val tie = listOf(word(10, "食A", "しょくA", "x", jlpt = 3), word(11, "食B", "しょくB", "x", jlpt = 5))
        assertEquals(listOf(11, 10), rankWords(tie, q).map { it.id }) // N5 before N3
    }

    @Test
    fun meaningAsAWholeWordBeatsAnyOtherPartOfTheMeaning() {
        val rain = word(20, "雨", "あめ", "rain")
        val brain = word(21, "頭脳", "ずのう", "brain; intelligence")
        assertEquals(listOf(20, 21), rankWords(listOf(brain, rain), SearchQuery.of("rain")).map { it.id })
    }

    // -- kanji -------------------------------------------------------------------------------------------

    private val dai = kanji("大", "large; big", "ダイ、タイ", "おお-、おお.きい、-おお.いに")
    private val ten = kanji("天", "heavens; sky", "テン", "あめ、あま-")
    private val kanjiList = listOf(ten, dai)

    @Test
    fun kanjiAreFoundByCharacterMeaningAndReading() {
        assertEquals(listOf("大"), rankKanjiList(kanjiList, SearchQuery.of("大")).map { it.literal })
        assertEquals(listOf("大"), rankKanjiList(kanjiList, SearchQuery.of("big")).map { it.literal })
        assertEquals(listOf("大"), rankKanjiList(kanjiList, SearchQuery.of("ookii")).map { it.literal }) // おおきい without the dot
        assertEquals(listOf("天"), rankKanjiList(kanjiList, SearchQuery.of("ten")).map { it.literal })    // テン on'yomi
        assertEquals(emptyList<String>(), rankKanjiList(kanjiList, SearchQuery.of("zzz")).map { it.literal })
    }

    @Test
    fun kunyomiShowsOkuriganaInBrackets() {
        assertEquals("おお-・おお(きい)・-おお(いに)", displayKunyomi("おお-、おお.きい、-おお.いに"))
        assertEquals("ダイ・タイ", displayOnyomi("ダイ、タイ"))
        assertEquals("おおきい", plainReading("おお.きい"))
    }
}
