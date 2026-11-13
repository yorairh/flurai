package com.flurai.app.core.media

import com.flurai.app.core.model.JlptLevel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTest {
    private fun word(id: Int, occurrences: Int) = DeckWord(id, WordForm("w$id"), occurrences)

    private val words = listOf(word(1, 600), word(2, 300), word(3, 100))

    @Test
    fun coverageWeighsWordsByHowOftenTheyOccur() {
        val c = coverage(words, known = setOf(1, 3))
        assertEquals(2, c.knownWords)
        assertEquals(3, c.checkedWords)
        assertEquals(0.7, c.occurrenceShare, 1e-9)
        assertEquals(2.0 / 3, c.wordShare, 1e-9)
    }

    @Test
    fun coverageOfNothingIsZero() {
        val c = coverage(emptyList(), setOf(1))
        assertEquals(0.0, c.occurrenceShare, 0.0)
        assertEquals(0.0, c.wordShare, 0.0)
    }

    @Test
    fun wordsToLearnAreCommonListedAndNotStarted() {
        val map = mapOf(1 to 101, 2 to 102) // word 3 is not in our lists
        assertEquals(listOf(101, 102), wordsToLearn(words, map, started = emptySet(), limit = 10))
        assertEquals(listOf(102), wordsToLearn(words, map, started = setOf(101), limit = 10))
        assertEquals(listOf(101), wordsToLearn(words, map, started = emptySet(), limit = 1))
    }

    @Test
    fun wordsWellAboveTheLearnersLevelAreLeftOut() {
        val map = mapOf(1 to 101, 2 to 102) // 101 is an N5 word, 102 an N1 word
        val levelOf = { id: Int -> if (id == 101) 5 else 1 }
        assertEquals(listOf(101), wordsToLearn(words, map, emptySet(), 10, levelOf, hardestAllowed = hardestWordToOffer(JlptLevel.N5)))
        assertEquals(listOf(101, 102), wordsToLearn(words, map, emptySet(), 10, levelOf, hardestAllowed = 1))
    }

    @Test
    fun allWordsOfATitleAreReadPageByPage() = runBlocking {
        val all = (1..5).map { word(it, 100 - it) }
        val asked = ArrayList<Int>()
        val got = allDeckWords { offset ->
            asked += offset
            VocabularyPage(DeckVocabulary(all.drop(offset).take(2)), totalItems = all.size)
        }
        assertEquals(all, got)
        assertEquals(listOf(0, 2, 4), asked)
    }

    @Test
    fun readingStopsAtAnEmptyPageOrThePageLimit() = runBlocking {
        // The total claims more words than there are.
        assertEquals(1, allDeckWords { offset -> VocabularyPage(DeckVocabulary(if (offset == 0) listOf(word(1, 9)) else emptyList()), totalItems = 50) }.size)
        // A server that never runs out is cut off.
        assertEquals(3, allDeckWords(maxPages = 3) { offset -> VocabularyPage(DeckVocabulary(listOf(word(offset, 1))), totalItems = 1000) }.size)
    }

    @Test
    fun allListedWordsKeepStartedWordsAndTheMostCommonFirst() {
        val map = mapOf(3 to 103, 1 to 101) // word 2 is not in our lists
        assertEquals(listOf(101, 103), allListedWords(words.reversed(), map))
    }

    @Test
    fun oneLevelAboveTheLearnerIsStillOffered() {
        assertEquals(4, hardestWordToOffer(JlptLevel.N5))
        assertEquals(4, hardestWordToOffer(JlptLevel.Beginner))
        assertEquals(2, hardestWordToOffer(JlptLevel.N3))
        assertEquals(1, hardestWordToOffer(JlptLevel.N1))
    }

    @Test
    fun difficultyRangesRiseWithTheLevel() {
        val levels = listOf(JlptLevel.N5, JlptLevel.N4, JlptLevel.N3, JlptLevel.N2, JlptLevel.N1)
        levels.zipWithNext().forEach { (easy, hard) ->
            assertTrue(difficultyRange(easy).start < difficultyRange(hard).start)
        }
        assertEquals(difficultyRange(JlptLevel.N5), difficultyRange(JlptLevel.Beginner))
    }

    @Test
    fun parsesAJitenListingAndIgnoresUnknownFields() {
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        val page = json.decodeFromString(
            DeckPage.serializer(),
            """{"data":[{"deckId":34772,"coverName":"https://cdn.jiten.moe/34772/cover.jpg","mediaType":1,
            "originalTitle":"からかい上手の高木さん","romajiTitle":"Karakai Jouzu no Takagi-san","englishTitle":"Teasing Master Takagi-san",
            "uniqueWordCount":2480,"difficulty":0,"difficultyRaw":0.67,"franchiseId":174,"tags":[{"tagId":7}],
            "links":[{"linkId":1,"linkType":4,"url":"https://anilist.co/anime/99468","deckId":34772}]}],
            "totalItems":120,"pageSize":50,"currentOffset":0}""",
        )
        assertEquals(1, page.data.size)
        assertEquals("Teasing Master Takagi-san", page.data[0].title)
        assertEquals(0.67, page.data[0].difficultyRaw, 0.0)
        assertEquals(120, page.totalItems)
    }

    @Test
    fun titleFallsBackToRomajiThenOriginal() {
        assertEquals("Romaji", Deck(1, romajiTitle = "Romaji", originalTitle = "原題").title)
        assertEquals("原題", Deck(1, originalTitle = "原題").title)
    }
}
