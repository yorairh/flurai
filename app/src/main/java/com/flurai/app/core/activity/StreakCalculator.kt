package com.flurai.app.core.activity

import com.flurai.app.core.study.StudyDay
import java.time.Instant
import java.time.ZoneId

/** The epoch day of the study day [now] belongs to (a session at 2 a.m. counts toward the evening day). */
fun studyEpochDay(now: Instant, zone: ZoneId = ZoneId.systemDefault()): Long =
    StudyDay.start(now, zone).atZone(zone).toLocalDate().toEpochDay()

data class StreakStats(val current: Int, val longest: Int, val todayMet: Boolean)

/**
 * A day counts toward the streak once it has at least [minSeconds] of study. The current streak
 * runs back from today if today is met, otherwise from yesterday: today is not over yet, so it
 * does not break the streak until the day ends.
 */
fun computeStreaks(secondsByDay: Map<Long, Int>, today: Long, minSeconds: Int): StreakStats {
    val met = secondsByDay.filterValues { it >= minSeconds && it > 0 }.keys
    val todayMet = today in met
    var current = 0
    var day = if (todayMet) today else today - 1
    while (day in met) { current++; day-- }

    var longest = 0
    var run = 0
    var previous: Long? = null
    for (d in met.sorted()) {
        run = if (previous != null && d == previous + 1) run + 1 else 1
        longest = maxOf(longest, run)
        previous = d
    }
    return StreakStats(current, longest, todayMet)
}

/** What the Home card shows. */
data class ActivitySummary(
    val todaySeconds: Int,
    val goalSeconds: Int,
    val streak: StreakStats,
    /** Seconds studied on each of the last 35 days, oldest first (the last item is today). */
    val heat: List<Int>,
    val weekSeconds: Int,
    val minSeconds: Int,
    /** The study day number of today (days since 1970), to label the days of the heat map. */
    val today: Long = 0,
) {
    val todayProgress: Float get() = if (goalSeconds <= 0) 1f else (todaySeconds.toFloat() / goalSeconds).coerceIn(0f, 1f)
}

fun summarize(days: List<StudyDayEntity>, today: Long, goalMinutes: Int, streakMinMinutes: Int): ActivitySummary {
    val byDay = days.associate { it.day to it.seconds }
    val minSeconds = streakMinMinutes * 60
    return ActivitySummary(
        todaySeconds = byDay[today] ?: 0,
        goalSeconds = goalMinutes * 60,
        streak = computeStreaks(byDay, today, minSeconds),
        heat = (today - 34..today).map { byDay[it] ?: 0 },
        weekSeconds = (today - 6..today).sumOf { byDay[it] ?: 0 },
        minSeconds = minSeconds,
        today = today,
    )
}

/** "12 min", "<1 min", "1 h 05 min". */
fun formatStudyTime(seconds: Int): String {
    val minutes = seconds / 60
    return when {
        seconds <= 0 -> "0 min"
        minutes < 1 -> "<1 min"
        minutes < 60 -> "$minutes min"
        else -> "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')} min"
    }
}
