package com.flurai.app.core.study

import com.flurai.app.core.activity.SettingsRepository
import com.flurai.app.core.content.WordEntity
import com.flurai.app.core.model.JlptLevel
import com.flurai.app.core.profile.StudyProfile
import com.flurai.app.core.activity.StudyClock
import com.flurai.app.core.srs.Rating
import com.flurai.app.core.srs.SrsState
import com.flurai.app.core.prefs.InMemoryPrefs
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * One flashcard session from the learner's side: what is on screen after each tap. Content, the
 * database and the clock are in memory, so a test can also move time.
 */
class StudySessionTest {
    private var time = Instant.parse("2026-03-10T03:00:00Z")
    private val dao = FakeStudyDao()
    private val deckWords = mutableMapOf<Long, List<Int>>()
    private val repo = StudyRepository(
        dao, FakeContentDao(), SettingsRepository(InMemoryPrefs()),
        object : DeckWords { override suspend fun wordIds(deckId: Long) = deckWords[deckId].orEmpty() },
    )

    /** (seconds, reviews) credited to today's study time, in order. */
    private val credited = mutableListOf<Pair<Int, Int>>()

    /** Words whose content no longer exists. */
    private val missing = mutableSetOf<Int>()
    private val content = object : CardContentSource {
        override suspend fun contentFor(item: ItemRef): CardContent? = when {
            item.itemType == ItemType.Kana -> KanaData.byId(item.itemId)?.let { CardContent.KanaCard(it) }
            item.itemId in missing -> null
            else -> CardContent.Word(WordEntity(item.itemId, "語${item.itemId}", "ご${item.itemId}", null, "meaning ${item.itemId}", 5, null, null, null), null)
        }
    }

    private fun profile(newPerDay: Int = 10, level: JlptLevel = JlptLevel.N5) = StudyProfile(level, 15, newPerDay, true, true)

    private fun session(profile: StudyProfile = profile(), source: StudySource = StudySource.Auto) = StudySession(
        "u", profile, source, repo, content,
        StudyClock({ "u" }, { time }) { _, _, seconds, reviews -> credited += seconds to reviews },
        now = { time },
    )

    private fun card(id: Int, state: SrsState = SrsState.Review, due: Instant = time, kind: CardKind = CardKind.Recognition) = CardEntity(
        uid = "u", itemType = ItemType.Word, itemId = id, kind = kind, state = state.value, step = if (state == SrsState.Review) null else 0,
        stability = 5.0, difficulty = 5.0, due = due.toEpochMilli(), lastReview = time.minusSeconds(86_400).toEpochMilli(), reps = 2,
        lapses = 0, suspended = false, buriedUntil = null, createdAt = 1,
    )

    private suspend fun StudySession.answerCurrent(rating: Rating = Rating.Easy) {
        time = time.plusSeconds(10)
        reveal()
        time = time.plusSeconds(10)
        answer(rating)
    }

    private fun StudySession.currentWord() = (state.value.current!!.content as CardContent.Word).word.id

    // -- the normal run ------------------------------------------------------------------------------

    @Test
    fun newWordsComeInTeachingOrderAndTheSessionEndsWhenTheyAreDone() = runTest {
        val s = session(profile(newPerDay = 3))
        s.start()

        assertFalse(s.state.value.loading)
        assertEquals(3, s.currentWord()) // the Genki lesson-1 word comes first
        assertTrue(s.state.value.current!!.isNew)
        assertEquals(QueueCounts(new = 3, learning = 0, review = 0), s.state.value.counts)
        assertEquals(setOf(Rating.Again, Rating.Hard, Rating.Good, Rating.Easy), s.state.value.intervals.keys)

        s.answerCurrent()
        assertEquals(1, s.currentWord())
        s.answerCurrent()
        assertEquals(2, s.currentWord())
        s.answerCurrent()

        assertTrue(s.state.value.finished)
        assertNull(s.state.value.current)
        assertEquals(3, s.state.value.answeredCount)
        assertEquals(setOf(1, 2, 3), dao.cards.value.values.filter { it.kind == CardKind.Recognition }.map { it.itemId }.toSet())
    }

    @Test
    fun anAnswerBeforeTheCardIsRevealedIsIgnored() = runTest {
        val s = session()
        s.start()
        val first = s.state.value.current

        s.answer(Rating.Good)

        assertEquals(first, s.state.value.current)
        assertEquals(0, s.state.value.answeredCount)
        assertTrue(dao.cards.value.isEmpty())
    }

    @Test
    fun revealingShowsTheAnswerAndTheNextCardStartsHidden() = runTest {
        val s = session()
        s.start()
        s.reveal()
        assertTrue(s.state.value.revealed)

        s.answer(Rating.Easy)
        assertFalse(s.state.value.revealed)
    }

    @Test
    fun aCardAnsweredAgainComesBackInTheSameSession() = runTest {
        val s = session(profile(newPerDay = 1))
        s.start()
        val word = s.currentWord()

        s.answerCurrent(Rating.Again)

        assertEquals(word, s.currentWord()) // still learning: shown again within the learn-ahead window
        assertFalse(s.state.value.current!!.isNew)
    }

    @Test
    fun whenNothingIsLeftTheFinishedScreenOffersTheLevelsThatStillHaveWords() = runTest {
        val s = session(profile(newPerDay = 1))
        s.start()
        s.answerCurrent()

        assertTrue(s.state.value.finished)
        assertEquals(setOf(4, 5), s.state.value.levelsWithWords)
        assertFalse(s.state.value.deckSession)
    }

    // -- nothing to study --------------------------------------------------------------------------

    @Test
    fun withNoWordsAndNoDueCardsTheSessionIsFinishedAtOnce() = runTest {
        val s = session(profile(newPerDay = 0))
        s.start()

        assertTrue(s.state.value.finished)
        assertFalse(s.state.value.loading)
        assertNull(s.state.value.comeBackAt)
    }

    @Test
    fun aLearningCardNotDueYetMakesTheScreenWaitAndRefreshShowsItWhenItIs() = runTest {
        dao.upsertCard(card(1, SrsState.Learning, due = time.plus(Duration.ofMinutes(30)))) // beyond the 20-minute learn-ahead
        val s = session(profile(newPerDay = 0))
        s.start()

        assertTrue(s.state.value.finished)
        assertEquals(time.plus(Duration.ofMinutes(30)), s.state.value.comeBackAt)

        s.refresh() // too early: still waiting
        assertTrue(s.state.value.finished)

        time = time.plus(Duration.ofMinutes(31))
        s.refresh()
        assertFalse(s.state.value.finished)
        assertEquals(1, s.currentWord())
        assertNull(s.state.value.comeBackAt)
    }

    @Test
    fun cardsUnlockedForTomorrowAreNotInTodaysSession() = runTest {
        val s = session(profile(newPerDay = 1))
        s.start()
        s.answerCurrent() // graduates the word: its Production and Listening cards unlock for after the rollover

        assertTrue(s.state.value.finished)
        assertTrue(dao.cards.value.values.any { it.kind == CardKind.Production })
        assertEquals(0, s.state.value.counts.review)
    }

    // -- cards that cannot be shown ------------------------------------------------------------------

    @Test
    fun aWordWhoseContentIsGoneIsSkipped() = runTest {
        missing += 3
        val s = session(profile(newPerDay = 3))
        s.start()

        assertEquals(1, s.currentWord())
        assertEquals(2, s.state.value.counts.new)
    }

    @Test
    fun buryingAnExistingCardHidesItUntilTomorrow() = runTest {
        dao.upsertCard(card(1))
        dao.upsertCard(card(2))
        val s = session(profile(newPerDay = 0))
        s.start()
        val buried = s.currentWord()

        s.buryCurrent()

        val row = dao.cards.value.values.single { it.itemId == buried }
        assertNotNull(row.buriedUntil)
        assertEquals(1, s.state.value.counts.review)
        assertTrue(s.currentWord() != buried)
    }

    @Test
    fun suspendingACardKeepsItOutOfTheSession() = runTest {
        dao.upsertCard(card(1))
        val s = session(profile(newPerDay = 0))
        s.start()

        s.suspendCurrent()

        assertTrue(dao.cards.value.values.single().suspended)
        assertTrue(s.state.value.finished)
    }

    @Test
    fun skippingABrandNewWordLeavesNoCardBehind() = runTest {
        val s = session(profile(newPerDay = 2))
        s.start()
        val skipped = s.currentWord()

        s.suspendCurrent()

        assertTrue(dao.cards.value.isEmpty())
        assertTrue(s.currentWord() != skipped)
    }

    // -- asking for more -----------------------------------------------------------------------------

    @Test
    fun learnMoreAddsWordsFromTheSameDeckAndStopsWhenItIsUsedUp() = runTest {
        deckWords[1] = listOf(1, 2, 3)
        val s = session(profile(newPerDay = 2), StudySource.Deck(1))
        s.start()
        s.answerCurrent()
        s.answerCurrent()

        assertTrue(s.state.value.finished)
        assertTrue(s.state.value.deckSession)
        assertTrue(s.state.value.deckHasMore)
        assertTrue(s.state.value.levelsWithWords.isEmpty()) // a chosen deck never offers other levels

        s.learnMore()
        assertEquals(3, s.currentWord())
        s.answerCurrent()

        assertTrue(s.state.value.finished)
        assertFalse(s.state.value.deckHasMore)
        s.learnMore()
        assertTrue(s.state.value.noMoreWords)
        assertTrue(s.state.value.finished)
    }

    @Test
    fun aChosenDeckIgnoresTodaysNewWordLimit() = runTest {
        deckWords[1] = listOf(1, 2)
        // The limit is already used up by studying word 3 automatically...
        val auto = session(profile(newPerDay = 1))
        auto.start()
        auto.answerCurrent()

        // ...but a deck the learner picked still gives its words.
        val s = session(profile(newPerDay = 1), StudySource.Deck(1))
        s.start()
        assertFalse(s.state.value.finished)
        assertEquals(1, s.currentWord())
    }

    // -- listening and kana ------------------------------------------------------------------------

    @Test
    fun aListeningCardIsShownAsAListeningCard() = runTest {
        dao.upsertCard(card(1, kind = CardKind.Listening))
        val s = session(profile(newPerDay = 0))
        s.start()

        assertEquals(CardKind.Listening, s.state.value.current!!.item.kind)
    }

    @Test
    fun kanaCardsShowKana() = runTest {
        val s = session(profile(level = JlptLevel.Beginner, newPerDay = 2).copy(knowsHiragana = false))
        s.start()

        assertTrue(s.state.value.current!!.content is CardContent.KanaCard)
    }

    // -- study time ----------------------------------------------------------------------------------

    @Test
    fun everyAnswerCountsAsAReviewAndTheTimeBetweenTapsIsCredited() = runTest {
        val s = session(profile(newPerDay = 2))
        s.start()
        s.answerCurrent()
        s.answerCurrent()

        assertEquals(2, credited.sumOf { it.second })
        assertEquals(40, credited.sumOf { it.first }) // four taps (reveal, answer, reveal, answer), ten seconds apart
    }

    @Test
    fun theClockStopsInTheBackgroundAndRestartsOnReturn() = runTest {
        val s = session(profile(newPerDay = 2))
        s.start()
        time = time.plusSeconds(20)
        s.pause()
        time = time.plusSeconds(3_600) // away
        s.resume()
        time = time.plusSeconds(5)
        s.reveal()

        assertEquals(25, credited.sumOf { it.first }) // 20 before leaving + 5 after returning, not the hour away
    }
}
