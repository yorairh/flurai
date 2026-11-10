package com.flurai.app.core.sync

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.flurai.app.core.activity.StudyDayEntity
import com.flurai.app.core.grammar.GrammarProgressEntity
import com.flurai.app.core.study.CardEntity
import com.flurai.app.core.study.CardKind
import com.flurai.app.core.study.ItemType

/** Local reads and writes the cloud sync needs, on top of the DAOs the screens use (see [SyncLocal]). */
@Dao
interface SyncDao {
    @Query("SELECT * FROM card WHERE uid = :uid")
    suspend fun cards(uid: String): List<CardEntity>

    @Query("SELECT * FROM card WHERE uid = :uid AND item_type = :type AND item_id = :id AND kind = :kind")
    suspend fun card(uid: String, type: ItemType, id: Int, kind: CardKind): CardEntity?

    @Upsert
    suspend fun upsertCard(card: CardEntity)

    @Query("SELECT * FROM study_day WHERE uid = :uid AND day = :day")
    suspend fun day(uid: String, day: Long): StudyDayEntity?

    @Upsert
    suspend fun upsertDay(day: StudyDayEntity)

    @Query("SELECT COUNT(*) FROM test_result WHERE uid = :uid AND type = :type AND taken_at = :takenAt")
    suspend fun testCount(uid: String, type: String, takenAt: Long): Int

    @Query("SELECT * FROM test_result WHERE uid = :uid")
    suspend fun tests(uid: String): List<com.flurai.app.core.quiz.TestResultEntity>

    @Query("SELECT * FROM grammar_progress WHERE uid = :uid")
    suspend fun grammar(uid: String): List<GrammarProgressEntity>

    @Query("SELECT * FROM custom_channel WHERE uid = :uid ORDER BY added_at")
    suspend fun channels(uid: String): List<com.flurai.app.core.video.CustomChannelEntity>
}
