package com.flurai.app.core.activity

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Dao
interface StudyDayDao {
    @Query(
        """
        INSERT INTO study_day (uid, day, seconds, reviews) VALUES (:uid, :day, :seconds, :reviews)
        ON CONFLICT(uid, day) DO UPDATE SET seconds = seconds + :seconds, reviews = reviews + :reviews
        """,
    )
    suspend fun add(uid: String, day: Long, seconds: Int, reviews: Int)

    @Query("SELECT * FROM study_day WHERE uid = :uid AND day >= :since ORDER BY day")
    fun since(uid: String, since: Long): Flow<List<StudyDayEntity>>

    @Query("SELECT * FROM study_day WHERE uid = :uid")
    suspend fun all(uid: String): List<StudyDayEntity>
}

/** Saves and reads daily study time. Writes run in the app's own scope so leaving a screen cannot lose them. */
@Singleton
class StudyActivityRepository @Inject constructor(private val dao: StudyDayDao) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun record(uid: String, now: Instant, seconds: Int, reviews: Int) {
        if (seconds <= 0 && reviews <= 0) return
        scope.launch { dao.add(uid, studyEpochDay(now), seconds.coerceAtLeast(0), reviews) }
    }

    /** The last [days] study days, for streaks and the heat map. */
    fun recent(uid: String, now: Instant, days: Int = 400): Flow<List<StudyDayEntity>> =
        dao.since(uid, studyEpochDay(now) - days)

    suspend fun all(uid: String): List<StudyDayEntity> = dao.all(uid)
}

/**
 * Counts study time between taps. A gap longer than [IDLE_CAP_SECONDS] only counts that much
 * (the learner wandered off), and nothing counts while stopped (app in the background).
 */
class ActivityTimer(private val clock: () -> Instant = Instant::now) {
    private var last: Instant? = null

    fun start() { last = clock() }

    /** Seconds to credit since the last tap or start, then restart the clock. Zero if stopped. */
    fun tick(): Int {
        val before = last ?: return 0
        val now = clock()
        last = now
        return Duration.between(before, now).seconds.coerceIn(0, IDLE_CAP_SECONDS).toInt()
    }

    /** Credits the time so far and stops counting until [start]. */
    fun stop(): Int = tick().also { last = null }

    companion object { const val IDLE_CAP_SECONDS = 120L }
}
