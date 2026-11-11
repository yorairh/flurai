package com.flurai.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.flurai.app.core.activity.ActivitySummary
import com.flurai.app.core.activity.formatStudyTime
import com.flurai.app.ui.theme.japanese
import androidx.compose.ui.text.style.TextAlign
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

/** Today's study time against the goal, the day streak, and the last five weeks as a heat map. */
@Composable
fun StreakCard(summary: ActivitySummary, now: LocalTime = LocalTime.now()) {
    val streak = summary.streak
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("連続", style = MaterialTheme.typography.titleMedium.japanese(), color = MaterialTheme.colorScheme.secondary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The ring fills toward today's goal; the number inside is the streak, in days.
                Box(contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(progress = { summary.todayProgress }, modifier = Modifier.size(72.dp), strokeWidth = 7.dp)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${streak.current}", style = MaterialTheme.typography.titleLarge)
                        Text(if (streak.current == 1) "day" else "days", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("${streak.current}-day streak", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Today ${formatStudyTime(summary.todaySeconds)} of ${summary.goalSeconds / 60} min goal",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Best ${streak.longest} · this week ${formatStudyTime(summary.weekSeconds)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val message = streakMessage(summary, now.hour)
            if (message != null) {
                Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            HeatMap(summary.heat, summary.minSeconds, summary.today)
        }
    }
}

/** An encouraging line: a warning in the evening when the streak is about to lapse, a cheer on milestones. */
internal fun streakMessage(summary: ActivitySummary, hour: Int): String? {
    val streak = summary.streak
    val minutes = (summary.minSeconds / 60).coerceAtLeast(1)
    return when {
        !streak.todayMet && streak.current > 0 && hour >= 18 -> "Study $minutes min today to keep your ${streak.current}-day streak!"
        !streak.todayMet && streak.current == 0 -> "Study $minutes min today to start a streak."
        streak.todayMet && streak.current in MILESTONES -> "${streak.current} days in a row. Amazing!"
        streak.todayMet -> "Today counts. See you tomorrow!"
        else -> null
    }
}

private val MILESTONES = setOf(7, 30, 100, 365)

/** 5 weeks of 7 days, oldest first, with the weekday over each column; darker means more study. */
@Composable
private fun HeatMap(heat: List<Int>, minSeconds: Int, today: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (today > 0) {
            // Every row is seven days, so a column is always the same weekday.
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (0 until 7).forEach { column ->
                    val day = LocalDate.ofEpochDay(today - (heat.size - 1) + column).dayOfWeek
                    Text(
                        day.getDisplayName(TextStyle.SHORT, Locale.US).take(2),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        heat.chunked(7).forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                week.forEach { seconds -> Cell(seconds, minSeconds, Modifier.weight(1f)) }
            }
        }
        val minutes = (minSeconds / 60).coerceAtLeast(1)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            listOf(0 to "none", minSeconds / 2 to "under $minutes min", minSeconds to "$minutes min+", minSeconds * 3 to "${minutes * 3} min+").forEach { (seconds, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Cell(seconds, minSeconds, Modifier.width(12.dp), height = 12.dp)
                    Spacer(Modifier.width(4.dp))
                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Cell(seconds: Int, minSeconds: Int, modifier: Modifier, height: Dp = 20.dp) {
    val primary = MaterialTheme.colorScheme.primary
    val color = when {
        seconds <= 0 -> MaterialTheme.colorScheme.surfaceContainerHighest
        seconds < minSeconds -> primary.copy(alpha = 0.35f)
        seconds < minSeconds * 3 -> primary.copy(alpha = 0.7f)
        else -> primary
    }
    Box(modifier.height(height).clip(RoundedCornerShape(4.dp)).background(color))
}
