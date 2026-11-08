package com.flurai.app.core.study

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** What a card teaches: a JLPT word (content.db `word.id`) or a kana (`Kana.id`). */
enum class ItemType { Word, Kana }

/** Recognition: Japanese → meaning. Production: meaning → Japanese (unlocked once recognition is learned). */
enum class CardKind { Recognition, Production }

/**
 * One flashcard's scheduling state, per signed-in user (several people can share a phone).
 * Times are epoch milliseconds. A row exists from the moment the card is first shown.
 */
@Entity(
    tableName = "card",
    primaryKeys = ["uid", "item_type", "item_id", "kind"],
    indices = [Index("uid", "due")],
)
data class CardEntity(
    val uid: String,
    @ColumnInfo(name = "item_type") val itemType: ItemType,
    @ColumnInfo(name = "item_id") val itemId: Int,
    val kind: CardKind,
    /** SrsState value: 1 learning, 2 review, 3 relearning. */
    val state: Int,
    val step: Int?,
    val stability: Double?,
    val difficulty: Double?,
    val due: Long,
    @ColumnInfo(name = "last_review") val lastReview: Long?,
    val reps: Int,
    val lapses: Int,
    val suspended: Boolean,
    /** Hidden from study until this time (buried for the rest of the day). */
    @ColumnInfo(name = "buried_until") val buriedUntil: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** Every answer ever given; the history behind the level estimate and future sync. */
@Entity(tableName = "review_log", indices = [Index("uid", "reviewed_at")])
data class ReviewLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uid: String,
    @ColumnInfo(name = "item_type") val itemType: ItemType,
    @ColumnInfo(name = "item_id") val itemId: Int,
    val kind: CardKind,
    /** Rating value 1–4 (Again, Hard, Good, Easy). */
    val rating: Int,
    @ColumnInfo(name = "reviewed_at") val reviewedAt: Long,
    @ColumnInfo(name = "state_before") val stateBefore: Int,
    /** Time until the card is due again, in milliseconds. */
    @ColumnInfo(name = "interval_ms") val intervalMs: Long,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
)
