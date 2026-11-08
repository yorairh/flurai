package com.flurai.app.core.study

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * The learner's own data (cards, review history). Separate from the read-only ContentDatabase so
 * a content update never touches progress. Schema changes here need real migrations.
 */
@Database(entities = [CardEntity::class, ReviewLogEntity::class], version = 1, exportSchema = true)
abstract class UserDatabase : RoomDatabase() {
    abstract fun studyDao(): StudyDao
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
