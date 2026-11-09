package com.flurai.app.core.study

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.flurai.app.core.activity.StudyDayDao
import com.flurai.app.core.activity.StudyDayEntity
import com.flurai.app.core.grammar.GrammarProgressDao
import com.flurai.app.core.grammar.GrammarProgressEntity
import com.flurai.app.core.quiz.TestResultDao
import com.flurai.app.core.video.CustomChannelDao
import com.flurai.app.core.video.CustomChannelEntity
import com.flurai.app.core.quiz.TestResultEntity
import kotlinx.coroutines.flow.Flow

/**
 * The learner's own data (cards, review history). Separate from the read-only ContentDatabase so
 * a content update never touches progress. Schema changes here need real migrations.
 */
@Database(entities = [CardEntity::class, ReviewLogEntity::class, StudyDayEntity::class, TestResultEntity::class, GrammarProgressEntity::class, CustomChannelEntity::class], version = 5, exportSchema = true)
abstract class UserDatabase : RoomDatabase() {
    abstract fun studyDao(): StudyDao
    abstract fun studyDayDao(): StudyDayDao
    abstract fun testResultDao(): TestResultDao
    abstract fun grammarProgressDao(): GrammarProgressDao
    abstract fun customChannelDao(): CustomChannelDao
    abstract fun accountDao(): com.flurai.app.core.account.AccountDao
    abstract fun syncDao(): com.flurai.app.core.sync.SyncDao
}

/** 4 → 5: channels the learner added. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `custom_channel` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`uid` TEXT NOT NULL, `name` TEXT NOT NULL, `url` TEXT NOT NULL, `level` INTEGER NOT NULL, " +
                "`added_at` INTEGER NOT NULL)",
        )
    }
}

/** 3 → 4: how well each grammar point is known. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `grammar_progress` (`uid` TEXT NOT NULL, `grammar_id` INTEGER NOT NULL, " +
                "`correct` INTEGER NOT NULL, `wrong` INTEGER NOT NULL, `last_correct` INTEGER NOT NULL, " +
                "`last_at` INTEGER NOT NULL, PRIMARY KEY(`uid`, `grammar_id`))",
        )
    }
}

/** 2 → 3: test results (placement, checkpoints, quick quizzes). */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `test_result` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`uid` TEXT NOT NULL, `type` TEXT NOT NULL, `level` INTEGER, `correct` INTEGER NOT NULL, " +
                "`total` INTEGER NOT NULL, `taken_at` INTEGER NOT NULL)",
        )
    }
}

/** 1 → 2: daily study time (streaks). */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `study_day` (`uid` TEXT NOT NULL, `day` INTEGER NOT NULL, " +
                "`seconds` INTEGER NOT NULL, `reviews` INTEGER NOT NULL, PRIMARY KEY(`uid`, `day`))",
        )
    }
}

data class ItemRef(
    val itemType: ItemType,
    val itemId: Int,
)

data class StateCount(val state: Int, val count: Int)

@Dao
interface StudyDao {
    /** Cards to study before [until]: not suspended, not buried. */
    @Query(
        """
        SELECT * FROM card
        WHERE uid = :uid AND due <= :until AND NOT suspended AND (buried_until IS NULL OR buried_until <= :now)
        ORDER BY due
        """,
    )
    suspend fun dueCards(uid: String, until: Long, now: Long): List<CardEntity>

    @Query(
        """
        SELECT COUNT(*) FROM card
        WHERE uid = :uid AND due <= :until AND NOT suspended AND (buried_until IS NULL OR buried_until <= :now)
        """,
    )
    fun dueCount(uid: String, until: Long, now: Long): Flow<Int>

    /** Items that already have a card of any kind (so they aren't introduced again). */
    @Query("SELECT DISTINCT item_type AS itemType, item_id AS itemId FROM card WHERE uid = :uid")
    suspend fun introducedItems(uid: String): List<ItemRef>

    @Query("SELECT DISTINCT item_type AS itemType, item_id AS itemId FROM card WHERE uid = :uid")
    fun introducedItemsFlow(uid: String): Flow<List<ItemRef>>

    /** New items introduced since [since] (counts against the daily new-card limit). */
    @Query("SELECT COUNT(*) FROM card WHERE uid = :uid AND kind = 'Recognition' AND created_at >= :since")
    fun introducedSince(uid: String, since: Long): Flow<Int>

    @Query("SELECT * FROM card WHERE uid = :uid AND item_type = :itemType AND item_id = :itemId ORDER BY kind")
    fun cardsForItem(uid: String, itemType: ItemType, itemId: Int): Flow<List<CardEntity>>

    /** Every card of the user, for progress statistics. */
    @Query("SELECT * FROM card WHERE uid = :uid")
    fun allCards(uid: String): Flow<List<CardEntity>>

    @Query("SELECT state, COUNT(*) AS count FROM card WHERE uid = :uid GROUP BY state")
    fun stateCounts(uid: String): Flow<List<StateCount>>

    @Query("SELECT COUNT(*) FROM review_log WHERE uid = :uid AND reviewed_at >= :since")
    fun reviewsSince(uid: String, since: Long): Flow<Int>

    @Upsert
    suspend fun upsertCard(card: CardEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCardIfAbsent(card: CardEntity)

    @Insert
    suspend fun insertLog(log: ReviewLogEntity)

    @Transaction
    suspend fun recordReview(updated: CardEntity, log: ReviewLogEntity, unlocked: CardEntity?) {
        upsertCard(updated)
        insertLog(log)
        if (unlocked != null) insertCardIfAbsent(unlocked)
    }

    @Query("UPDATE card SET suspended = :suspended WHERE uid = :uid AND item_type = :itemType AND item_id = :itemId")
    suspend fun setSuspended(uid: String, itemType: ItemType, itemId: Int, suspended: Boolean)

    @Query("UPDATE card SET buried_until = :until WHERE uid = :uid AND item_type = :itemType AND item_id = :itemId")
    suspend fun bury(uid: String, itemType: ItemType, itemId: Int, until: Long)

    /** Forget an item: its cards go, so it's introduced again as new. Review history is kept. */
    @Query("DELETE FROM card WHERE uid = :uid AND item_type = :itemType AND item_id = :itemId")
    suspend fun resetItem(uid: String, itemType: ItemType, itemId: Int)
}
