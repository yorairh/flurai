package com.flurai.app.core.study

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** A word list the learner made (UserDatabase v6). Its words are in [DeckWordEntity]; the cards are shared, not copied. */
@Entity(tableName = "deck")
data class DeckEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uid: String,
    val name: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(tableName = "deck_word", primaryKeys = ["deck_id", "word_id"])
data class DeckWordEntity(
    @ColumnInfo(name = "deck_id") val deckId: Long,
    @ColumnInfo(name = "word_id") val wordId: Int,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

data class DeckMember(val deckId: Long, val wordId: Int)

/** What the study session needs from a deck: its word ids, in the order they were added. */
interface DeckWords {
    suspend fun wordIds(deckId: Long): List<Int>
}

@Dao
interface DeckDao {
    @Query("SELECT * FROM deck WHERE uid = :uid ORDER BY created_at, id")
    fun decks(uid: String): Flow<List<DeckEntity>>

    @Query("SELECT * FROM deck WHERE uid = :uid ORDER BY created_at, id")
    suspend fun decksNow(uid: String): List<DeckEntity>

    /** Every word of every deck of [uid], for the progress bars on the flashcards screen. */
    @Query("SELECT w.deck_id AS deckId, w.word_id AS wordId FROM deck_word w JOIN deck d ON d.id = w.deck_id WHERE d.uid = :uid")
    fun members(uid: String): Flow<List<DeckMember>>

    @Query("SELECT word_id FROM deck_word WHERE deck_id = :deckId ORDER BY added_at, word_id")
    suspend fun wordIds(deckId: Long): List<Int>

    @Query("SELECT word_id FROM deck_word WHERE deck_id = :deckId ORDER BY added_at, word_id")
    fun wordIdsFlow(deckId: Long): Flow<List<Int>>

    /** Ids of the decks of [uid] that contain [wordId]. */
    @Query("SELECT d.id FROM deck d JOIN deck_word w ON w.deck_id = d.id WHERE d.uid = :uid AND w.word_id = :wordId")
    fun decksWithWord(uid: String, wordId: Int): Flow<List<Long>>

    @Insert
    suspend fun insert(deck: DeckEntity): Long

    @Query("UPDATE deck SET name = :name WHERE uid = :uid AND id = :id")
    suspend fun rename(uid: String, id: Long, name: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addWord(word: DeckWordEntity)

    @Query("DELETE FROM deck_word WHERE deck_id = :deckId AND word_id = :wordId")
    suspend fun removeWord(deckId: Long, wordId: Int)

    @Query("DELETE FROM deck_word WHERE deck_id = :id")
    suspend fun clearWords(id: Long)

    @Query("DELETE FROM deck WHERE uid = :uid AND id = :id")
    suspend fun deleteRow(uid: String, id: Long)

    @Transaction
    suspend fun delete(uid: String, id: Long) {
        clearWords(id)
        deleteRow(uid, id)
    }

    @Transaction
    suspend fun create(deck: DeckEntity, wordIds: List<Int>, now: Long): Long {
        val id = insert(deck)
        wordIds.forEach { addWord(DeckWordEntity(id, it, now)) }
        return id
    }
}

const val MAX_DECK_NAME = 40

/** The name as stored: trimmed, single-spaced, cut to [MAX_DECK_NAME]. Null when nothing is left. */
fun cleanDeckName(input: String): String? =
    input.trim().replace(Regex("\\s+"), " ").take(MAX_DECK_NAME).trim().ifEmpty { null }

@Singleton
class DeckRepository @Inject constructor(private val dao: DeckDao) {
    fun decks(uid: String): Flow<List<DeckEntity>> = dao.decks(uid)

    fun members(uid: String): Flow<List<DeckMember>> = dao.members(uid)

    fun wordIds(deckId: Long): Flow<List<Int>> = dao.wordIdsFlow(deckId)

    fun decksWithWord(uid: String, wordId: Int): Flow<List<Long>> = dao.decksWithWord(uid, wordId)

    /** Makes a deck (optionally filled with [wordIds]). Null when the name is empty. */
    suspend fun create(uid: String, name: String, wordIds: List<Int> = emptyList()): Long? {
        val clean = cleanDeckName(name) ?: return null
        val now = System.currentTimeMillis()
        return dao.create(DeckEntity(uid = uid, name = clean, createdAt = now), wordIds, now)
    }

    suspend fun rename(uid: String, id: Long, name: String) {
        cleanDeckName(name)?.let { dao.rename(uid, id, it) }
    }

    suspend fun delete(uid: String, id: Long) = dao.delete(uid, id)

    suspend fun addWord(deckId: Long, wordId: Int) = dao.addWord(DeckWordEntity(deckId, wordId, System.currentTimeMillis()))

    suspend fun removeWord(deckId: Long, wordId: Int) = dao.removeWord(deckId, wordId)
}
