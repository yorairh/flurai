package com.flurai.app.core.activity

import com.flurai.app.core.reminder.delayUntil
import com.flurai.app.core.reminder.reminderText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class StreakCalculatorTest {
    private val min = 300 // 5 minutes
    private fun days(vararg d: Pair<Long, Int>) = mapOf(*d)

    @Test
    fun consecutiveDaysEndingTodayCount() {
        val s = computeStreaks(days(8L to 400, 9L to 300, 10L to 900), today = 10, minSeconds = min)
        assertEquals(StreakStats(current = 3, longest = 3, todayMet = true), s)
    }

    @Test
    fun todayNotYetMetDoesNotBreakTheStreak() {
        val s = computeStreaks(days(8L to 400, 9L to 400, 10L to 30), today = 10, minSeconds = min)
        assertEquals(2, s.current)
        assertFalse(s.todayMet)
    }

    @Test
    fun aMissedDayBreaksIt() {
        val s = computeStreaks(days(5L to 400, 6L to 400, 7L to 400, 8L to 100), today = 11, minSeconds = min)
        assertEquals(0, s.current)
        assertEquals(3, s.longest)
    }

    @Test
    fun dayBelowTheMinimumDoesNotCount() {
        assertEquals(0, computeStreaks(days(10L to 299), today = 10, minSeconds = min).current)
        assertEquals(1, computeStreaks(days(10L to 300), today = 10, minSeconds = min).current)
    }

    @Test
    fun longestStreakIsFoundInThePast() {
        val d = (1L..6L).associateWith { 600 } + (9L to 600) + (10L to 600)
        val s = computeStreaks(d, today = 10, minSeconds = min)
        assertEquals(2, s.current)
        assertEquals(6, s.longest)
    }

    @Test
    fun emptyHistory() {
        assertEquals(StreakStats(0, 0, false), computeStreaks(emptyMap(), today = 10, minSeconds = min))
    }

    @Test
    fun studyDayRollsOverAtFourInTheMorning() {
        val zone = ZoneId.of("UTC")
        val twoAm = Instant.parse("2026-03-10T02:00:00Z")
        val fiveAm = Instant.parse("2026-03-10T05:00:00Z")
        assertEquals(studyEpochDay(fiveAm, zone) - 1, studyEpochDay(twoAm, zone))
    }

    @Test
    fun summaryCoversFiveWeeksAndTheLastWeek() {
        val rows = listOf(
            StudyDayEntity("u", 100, 600, 10),
            StudyDayEntity("u", 98, 120, 2),
            StudyDayEntity("u", 50, 999, 5), // outside the heat map
        )
        val s = summarize(rows, today = 100, goalMinutes = 15, streakMinMinutes = 5)
        assertEquals(35, s.heat.size)
        assertEquals(600, s.heat.last())
        assertEquals(120, s.heat[s.heat.size - 3])
        assertEquals(720, s.weekSeconds)
        assertEquals(900, s.goalSeconds)
        assertEquals(600 / 900f, s.todayProgress, 0.001f)
    }

    @Test
    fun studyTimeFormatting() {
        assertEquals("0 min", formatStudyTime(0))
        assertEquals("<1 min", formatStudyTime(40))
        assertEquals("12 min", formatStudyTime(12 * 60 + 5))
        assertEquals("1 h 05 min", formatStudyTime(65 * 60))
    }

    @Test
    fun timerCapsIdleGapsAndStopsInTheBackground() {
        var now = Instant.EPOCH
        val timer = ActivityTimer { now }
        assertEquals(0, timer.tick()) // not started
        timer.start()
        now = now.plusSeconds(45)
        assertEquals(45, timer.tick())
        now = now.plusSeconds(600) // wandered off
        assertEquals(120, timer.stop())
        now = now.plusSeconds(60)
        assertEquals(0, timer.tick()) // stopped: the background does not count
        timer.start()
        now = now.plusSeconds(10)
        assertEquals(10, timer.tick())
    }

    @Test
    fun reminderWordingAdaptsToDueCardsAndStreak() {
        assertEquals("1 review waiting. Keep your 4-day streak going!", reminderText(due = 1, streak = 4))
        assertTrue(reminderText(due = 0, streak = 9).contains("9-day streak"))
        assertEquals("12 reviews waiting for you.", reminderText(due = 12, streak = 0))
        assertEquals("Ready for a few minutes of Japanese?", reminderText(0, 0))
    }

    @Test
    fun reminderDelayIsTodayOrTomorrow() {
        val now = LocalDateTime.of(2026, 3, 10, 18, 30)
        assertEquals(Duration.ofMinutes(30), delayUntil(now, 19, 0))
        assertEquals(Duration.ofHours(23).plusMinutes(30), delayUntil(now, 18, 0))
        assertEquals(Duration.ofDays(1), delayUntil(now, 18, 30)) // exactly now means tomorrow
    }

    @Test
    fun studyClockCountsOnlyWhileRunningAndInFront() {
        var now = Instant.parse("2026-01-01T10:00:00Z")
        val saved = mutableListOf<Pair<Int, Int>>()
        val clock = StudyClock({ "u" }, { now }) { _, _, seconds, reviews -> saved += seconds to reviews }

        clock.resume() // not started yet: coming to the front must not start it
        now = now.plusSeconds(30)
        clock.tap()
        assertTrue(saved.isEmpty())

        clock.start()
        now = now.plusSeconds(20)
        clock.tap(reviews = 1)
        now = now.plusSeconds(10)
        clock.pause()
        now = now.plusSeconds(600) // in the background
        clock.resume()
        now = now.plusSeconds(5)
        clock.start() // already running: must not restart the count
        now = now.plusSeconds(5)
        clock.finish()
        now = now.plusSeconds(50)
        clock.resume() // finished: stays stopped
        clock.tap()

        assertEquals(listOf(20 to 1, 10 to 0, 10 to 0), saved)
    }
}
