package com.flurai.app.core.activity

import androidx.room.Entity

/** Study time per user and study day. [day] is the epoch day of the study day (4 a.m. rollover). */
@Entity(tableName = "study_day", primaryKeys = ["uid", "day"])
data class StudyDayEntity(
    val uid: String,
    val day: Long,
    val seconds: Int,
    val reviews: Int,
)
