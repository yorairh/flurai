package com.flurai.app.core.study

import com.flurai.app.core.prefs.InMemoryPrefs
import com.flurai.app.core.activity.SettingsRepository
import com.flurai.app.core.model.JlptLevel
import com.flurai.app.core.profile.StudyProfile
import com.flurai.app.core.srs.Rating
import com.flurai.app.core.srs.SrsState
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant

class StudyRepositoryTest {
    private val now = Instant.parse("2026-03-10T03:00:00Z")

    private val dao = FakeStudyDao()
    private val settings = SettingsRepository(InMemoryPrefs())
    private val deckWords = mutableMapOf<Long, List<Int>>()
    private val repo by lazy {
        StudyRepository(dao, FakeContentDao(), settings, object : DeckWords {
            override suspend fun wordIds(deckId: Long) = deckWords[deckId].orEmpty()
        })
    }

    private fun profile(level: JlptLevel, newPerDay: Int = 10, hiragana: Boolean = true, katakana: Boolean = true) =
        StudyProfile(level, 15, newPerDay, hiragana, katakana)

    @Test
    fun beginnersWhoCantReadKanaStartWithHiragana() = runTest {
        val session = repo.loadSession("u", profile(JlptLevel.Beginner, newPerDay = 3, hiragana = false), StudySource.Auto, now)
        assertEquals(listOf(1, 2, 3), session.newItems.map { it.itemId })
        assertEquals(setOf(ItemType.Kana), session.newItems.map { it.itemType }.toSet())
    }

    @Test
    fun autoTakesWordsFromTheStartingLevelInGenkiOrderThenMovesOn() = runTest {
        val n5 = repo.loadSession("u", profile(JlptLevel.N5), StudySource.Auto, now)
        assertEquals(listOf(3, 1, 2), n5.newItems.map { it.itemId })

        n5.newItems.forEach { repo.answer("u", QueueItem.New(it), Rating.Good, now, Duration.ZERO) }
        val later = repo.loadSession("u", profile(JlptLevel.N5), StudySource.Auto, now)
        assertEquals(listOf(10, 11), later.newItems.map { it.itemId })
    }

    @Test
    fun dailyNewLimitCountsWordsAlreadyIntroducedToday() = runTest {
        val first = repo.loadSession("u", profile(JlptLevel.N5, newPerDay = 2), StudySource.Auto, now)
        assertEquals(2, first.newItems.size)
        repo.answer("u", QueueItem.New(first.newItems[0]), Rating.Good, now, Duration.ZERO)
        val second = repo.loadSession("u", profile(JlptLevel.N5, newPerDay = 2), StudySource.Auto, now)
        assertEquals(1, second.newItems.size)
    }

    @Test
    fun learningAWordUnlocksItsProductionCardForTomorrow() = runTest {
        val word = ItemRef(ItemType.Word, 1)
        val learned = repo.answer("u", QueueItem.New(word), Rating.Easy, now, Duration.ZERO)
        assertEquals(SrsState.Review.value, learned.state)
        val production = dao.cards.value[Triple(ItemType.Word, 1, CardKind.Production)]
        assertNotNull(production)
        assertEquals(StudyDay.end(now).toEpochMilli(), production!!.due)
        assertNull(production.lastReview)
    }

    @Test
    fun learningAWordAlsoUnlocksAListeningCard() = runTest {
        repo.answer("u", QueueItem.New(ItemRef(ItemType.Word, 1)), Rating.Easy, now, Duration.ZERO)
        assertNotNull(dao.cards.value[Triple(ItemType.Word, 1, CardKind.Listening)])
    }

    @Test
    fun noListeningCardWhileTheSettingIsOff() = runTest {
        settings.update("u") { it.copy(listeningCards = false) }
        repo.answer("u", QueueItem.New(ItemRef(ItemType.Word, 1)), Rating.Easy, now, Duration.ZERO)
        assertNull(dao.cards.value[Triple(ItemType.Word, 1, CardKind.Listening)])
        assertNotNull(dao.cards.value[Triple(ItemType.Word, 1, CardKind.Production)])
    }

    @Test
    fun aDeckSessionOnlyOffersThatDecksWords() = runTest {
        deckWords[7] = listOf(2, 10) // one N5 and one N4 word
        val session = repo.loadSession("u", profile(JlptLevel.N5), StudySource.Deck(7), now)
        assertEquals(listOf(ItemRef(ItemType.Word, 2), ItemRef(ItemType.Word, 10)), session.newItems)
    }

    @Test
    fun aDeckSessionSkipsDueCardsOfOtherWords() = runTest {
        deckWords[7] = listOf(2)
        repo.answer("u", QueueItem.New(ItemRef(ItemType.Word, 1)), Rating.Again, now, Duration.ZERO)
        repo.answer("u", QueueItem.New(ItemRef(ItemType.Word, 2)), Rating.Again, now, Duration.ZERO)
        val session = repo.loadSession("u", profile(JlptLevel.N5), StudySource.Deck(7), now.plusSeconds(3600))
        assertEquals(listOf(2), session.cards.map { it.itemId })
    }

    @Test
    fun aLevelSessionOnlyReviewsThatLevelsWords() = runTest {
        repo.answer("u", QueueItem.New(ItemRef(ItemType.Word, 1)), Rating.Again, now, Duration.ZERO) // N5
        repo.answer("u", QueueItem.New(ItemRef(ItemType.Word, 10)), Rating.Again, now, Duration.ZERO) // N4
        val later = now.plusSeconds(3600)
        assertEquals(listOf(1), repo.loadSession("u", profile(JlptLevel.N5), StudySource.Level(5), later).cards.map { it.itemId })
        assertEquals(listOf(10), repo.loadSession("u", profile(JlptLevel.N5), StudySource.Level(4), later).cards.map { it.itemId })
        assertEquals(2, repo.loadSession("u", profile(JlptLevel.N5), StudySource.Auto, later).cards.size)
    }

    @Test
    fun aKanaSessionOnlyReviewsThatScript() = runTest {
        val hira = KanaData.of(KanaScript.Hiragana).first().id
        val kata = KanaData.of(KanaScript.Katakana).first().id
        repo.answer("u", QueueItem.New(ItemRef(ItemType.Kana, hira)), Rating.Again, now, Duration.ZERO)
        repo.answer("u", QueueItem.New(ItemRef(ItemType.Kana, kata)), Rating.Again, now, Duration.ZERO)
        val session = repo.loadSession("u", profile(JlptLevel.N5), StudySource.KanaDeck(KanaScript.Katakana), now.plusSeconds(3600))
        assertEquals(listOf(kata), session.cards.map { it.itemId })
    }

    @Test
    fun aDeckYouChoseIgnoresTodaysNewWordAllowance() = runTest {
        val p = profile(JlptLevel.N5, newPerDay = 2)
        repo.loadSession("u", p, StudySource.Auto, now).newItems.forEach { repo.answer("u", QueueItem.New(it), Rating.Good, now, Duration.ZERO) }
        assertEquals(0, repo.loadSession("u", p, StudySource.Auto, now).newItems.size)
        assertEquals(1, repo.loadSession("u", p, StudySource.Level(5), now).newItems.size)
    }

    @Test
    fun cardsUnlockedForTomorrowAreNotDueToday() = runTest {
        repo.answer("u", QueueItem.New(ItemRef(ItemType.Word, 1)), Rating.Easy, now, Duration.ZERO)
        assertEquals(3, dao.cards.value.size) // recognition, production and listening
        // Today: the learned recognition card is scheduled days ahead and the two unlocked cards wait for 4 a.m.
        val today = repo.loadSession("u", profile(JlptLevel.N5), StudySource.Auto, now.plusSeconds(60))
        assertEquals(emptyList<CardKind>(), today.cards.map { it.kind })
        // After the rollover the unlocked ones are due.
        val tomorrow = repo.loadSession("u", profile(JlptLevel.N5), StudySource.Auto, StudyDay.end(now).plusSeconds(60))
        assertEquals(setOf(CardKind.Production, CardKind.Listening), tomorrow.cards.map { it.kind }.toSet())
    }

    @Test
    fun sourceRoundTripsThroughNavigationArguments() {
        listOf(StudySource.Auto, StudySource.Level(4), StudySource.KanaDeck(KanaScript.Katakana), StudySource.Deck(12)).forEach {
            assertEquals(it, StudySource.decode(it.encode()))
        }
    }

    @Test
    fun kanaNeverGetProductionCards() = runTest {
        repo.answer("u", QueueItem.New(ItemRef(ItemType.Kana, 1)), Rating.Easy, now, Duration.ZERO)
        assertEquals(1, dao.cards.value.size)
    }

    @Test
    fun forgettingAReviewCountsALapseAndLogsTheAnswer() = runTest {
        val item = QueueItem.New(ItemRef(ItemType.Word, 2))
        val learned = repo.answer("u", item, Rating.Easy, now, Duration.ZERO)
        val later = now.plus(Duration.ofDays(9))
        val forgotten = repo.answer("u", QueueItem.Existing(learned), Rating.Again, later, Duration.ofSeconds(4))
        assertEquals(1, forgotten.lapses)
        assertEquals(SrsState.Relearning.value, forgotten.state)
        assertEquals(listOf(0, SrsState.Review.value), dao.logs.map { it.stateBefore })
        assertEquals(4000, dao.logs.last().durationMs)
    }

    @Test
    fun suspendedAndResetWordsLeaveTheQueue() = runTest {
        val word = ItemRef(ItemType.Word, 1)
        repo.answer("u", QueueItem.New(word), Rating.Again, now, Duration.ZERO)
        repo.setSuspended("u", word, true)
        assertEquals(0, repo.loadSession("u", profile(JlptLevel.N5), StudySource.Level(5), now.plusSeconds(120)).cards.size)
        repo.reset("u", word)
        val session = repo.loadSession("u", profile(JlptLevel.N5), StudySource.Level(5), now)
        assertEquals(listOf(3, 1, 2), session.newItems.map { it.itemId }) // word 1 is new again
    }

    @Test
    fun studySourceSurvivesNavigation() {
        listOf(StudySource.Auto, StudySource.Level(3), StudySource.KanaDeck(KanaScript.Katakana)).forEach {
            assertEquals(it, StudySource.decode(it.encode()))
        }
    }

    @Test
    fun aWordAddedByHandDoesNotUseUpTheDailyAllowanceEvenAfterItsFirstReview() = runTest {
        val word = ItemRef(ItemType.Word, 10)
        repo.addToStudy("u", word, now)
        val added = dao.cards.value.values.single()
        assertEquals(now.toEpochMilli(), added.createdAt) // its real creation time, so sync sees it as new

        val before = repo.loadSession("u", profile(JlptLevel.N5, newPerDay = 2), StudySource.Auto, now)
        assertEquals(2, before.newItems.size)

        repo.answer("u", QueueItem.Existing(added), Rating.Good, now, Duration.ZERO)
        val after = repo.loadSession("u", profile(JlptLevel.N5, newPerDay = 2), StudySource.Auto, now)
        assertEquals(2, after.newItems.size)
    }
}
