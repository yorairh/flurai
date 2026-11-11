package com.flurai.app.core.reminder

import com.flurai.app.core.auth.uid
import com.flurai.app.core.auth.AuthRepository
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.flurai.app.MainActivity
import com.flurai.app.R
import com.flurai.app.core.activity.SettingsRepository
import com.flurai.app.core.activity.StudyActivityRepository
import com.flurai.app.core.activity.computeStreaks
import com.flurai.app.core.activity.studyEpochDay
import com.flurai.app.core.study.StudyDao
import com.flurai.app.core.study.StudyDay
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/** Wording of the daily practice reminder, adapted to what is due and the streak. */
fun reminderText(due: Int, streak: Int): String = when {
    streak > 0 && due > 0 -> "$due ${if (due == 1) "review" else "reviews"} waiting. Keep your $streak-day streak going!"
    streak > 0 -> "Keep your $streak-day streak going. A few minutes is enough."
    due > 0 -> "$due ${if (due == 1) "review" else "reviews"} waiting for you."
    else -> "Ready for a few minutes of Japanese?"
}

/** Delay from [now] to the next [hour]:[minute] on the local clock. */
fun delayUntil(now: LocalDateTime, hour: Int, minute: Int): Duration {
    var target = now.toLocalDate().atTime(hour, minute)
    if (!target.isAfter(now)) target = target.plusDays(1)
    return Duration.between(now, target)
}

object ReminderScheduler {
    private const val WORK = "practice-reminder"
    const val CHANNEL = "practice"

    /** Schedules the daily reminder at [hour]:[minute], or cancels it. WorkManager keeps it across reboots. */
    fun apply(context: Context, enabled: Boolean, hour: Int, minute: Int) {
        val work = WorkManager.getInstance(context)
        if (!enabled) {
            work.cancelUniqueWork(WORK)
            return
        }
        val delay = delayUntil(LocalDateTime.now(), hour, minute)
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delay.toMinutes(), TimeUnit.MINUTES)
            .build()
        work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReminderDependencies {
    fun auth(): AuthRepository
    fun settings(): SettingsRepository
    fun activity(): StudyActivityRepository
    fun study(): StudyDao
}

/** Runs once a day (see [ReminderScheduler]); WorkManager builds it, so dependencies come from an entry point. */
class ReminderWorker(private val context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(context, ReminderDependencies::class.java)
        val uid = deps.auth().uid ?: return Result.success()
        val settings = deps.settings().settings(uid).first()
        if (!settings.reminderEnabled || !ReminderScheduler.canPost(context)) return Result.success()

        val now = Instant.now()
        val today = studyEpochDay(now)
        val days = deps.activity().all(uid).associate { it.day to it.seconds }
        val minSeconds = settings.streakMinutes * 60
        if ((days[today] ?: 0) >= minSeconds) return Result.success() // already practiced today

        val streak = computeStreaks(days, today, minSeconds).current
        val due = deps.study().dueCards(uid, StudyDay.end(now).toEpochMilli(), now.toEpochMilli(), settings.listeningCards).size
        post(reminderText(due, streak))
        return Result.success()
    }

    @Suppress("MissingPermission") // canPost() was checked in doWork
    private fun post(text: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(ReminderScheduler.CHANNEL, "Practice reminders", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Time for Japanese")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(1, notification)
    }
}
