package com.flurai.app.core.study

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A word list the learner made or imported (UserDatabase v6, [key] and [source] since v8). Its words are in
 * [DeckWordEntity]; the cards are shared, not copied.
 */
@Entity(tableName = "deck", indices = [Index(value = ["uid", "key"], unique = true)])
data class DeckEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uid: String,
    val name: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /**
     * The deck's lasting identity: its cloud document id and what "Studying from" a deck stores. [id] is
     * only this phone's row number. Decks made before v8 got [legacyDeckKey], so every phone agrees.
     */
    @ColumnInfo(defaultValue = "''") val key: String = newDeckKey(),
    /** Where an imported deck came from: `jiten:<title id>` or `anki:<deck id>`; null for a deck made by hand. */
    val source: String? = null,
)

fun newDeckKey(): String = UUID.randomUUID().toString()

/**
 * The key of a deck made before decks had keys: the same on every phone that has the deck (name and
 * creation time travel with it in the backup), so two phones migrating apart still agree.
 */
fun legacyDeckKey(name: String, createdAt: Long): String =
    UUID.nameUUIDFromBytes("deck|$createdAt|$name".toByteArray()).toString()

/** The `source` of a deck made from a jiten.moe title. */
fun jitenSource(titleId: Int) = "jiten:$titleId"

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

    /** The words of [uid]'s deck with [key], in order; null when there is no such deck (it was deleted). */
    suspend fun wordIdsByKey(uid: String, key: String): List<Int>? = null
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

    @Query("SELECT * FROM deck WHERE uid = :uid AND `key` = :key")
    suspend fun byKey(uid: String, key: String): DeckEntity?

    @Query("SELECT * FROM deck WHERE uid = :uid AND source = :source ORDER BY created_at LIMIT 1")
    suspend fun bySource(uid: String, source: String): DeckEntity?

    @Query("SELECT MAX(added_at) FROM deck_word WHERE deck_id = :deckId")
    suspend fun lastAddedAt(deckId: Long): Long?

    @Insert
    suspend fun insert(deck: DeckEntity): Long

    @Query("UPDATE deck SET name = :name, created_at = :createdAt, source = :source WHERE id = :id")
    suspend fun update(id: Long, name: String, createdAt: Long, source: String?)

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

    @Query("DELETE FROM deck_word WHERE deck_id IN (SELECT id FROM deck WHERE uid = :uid)")
    suspend fun clearAllWords(uid: String)

    @Query("DELETE FROM deck WHERE uid = :uid")
    suspend fun deleteAll(uid: String)

    /** Makes [decks] (each with its word ids) the whole list of [uid] (the cloud's copy replacing this phone's). */
    @Transaction
    suspend fun replaceAll(uid: String, decks: List<Pair<DeckEntity, List<Int>>>) {
        clearAllWords(uid)
        deleteAll(uid)
        decks.forEach { (deck, words) -> create(deck, words, deck.createdAt) }
    }

    /**
     * Makes [uid]'s deck with [deck]'s key look like [deck] with exactly [wordIds] (the cloud's copy
     * replacing this phone's), creating it when this phone has none. The row keeps its id.
     */
    @Transaction
    suspend fun put(uid: String, deck: DeckEntity, wordIds: List<Int>) {
        val existing = byKey(uid, deck.key)
        if (existing == null) {
            create(deck.copy(id = 0, uid = uid), wordIds, deck.createdAt)
        } else {
            update(existing.id, deck.name, deck.createdAt, deck.source)
            clearWords(existing.id)
            wordIds.distinct().forEachIndexed { i, wordId -> addWord(DeckWordEntity(existing.id, wordId, deck.createdAt + i)) }
        }
    }

    @Transaction
    suspend fun deleteByKey(uid: String, key: String) {
        byKey(uid, key)?.let { delete(uid, it.id) }
    }

    /** Adds the words of [wordIds] the deck does not have yet, after its last word, in the given order. Returns how many were added. */
    @Transaction
    suspend fun append(deckId: Long, wordIds: List<Int>, now: Long): Int {
        val have = wordIds(deckId).toHashSet()
        val start = maxOf(now, (lastAddedAt(deckId) ?: 0L) + 1)
        val added = wordIds.distinct().filter { it !in have }
        added.forEachIndexed { i, wordId -> addWord(DeckWordEntity(deckId, wordId, start + i)) }
        return added.size
    }

    @Transaction
    suspend fun create(deck: DeckEntity, wordIds: List<Int>, now: Long): Long {
        val id = insert(deck)
        // A millisecond apart, so the deck keeps the order of the list (wordIds sorts by added_at).
        wordIds.distinct().forEachIndexed { i, wordId -> addWord(DeckWordEntity(id, wordId, now + i)) }
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

    suspend fun byKey(uid: String, key: String): DeckEntity? = dao.byKey(uid, key)

    suspend fun bySource(uid: String, source: String): DeckEntity? = dao.bySource(uid, source)

    /**
     * Puts [wordIds] into the deck that came from [source]: a new deck called [name], or (imported
     * before) the same deck, which keeps its words and gets the missing ones after them.
     */
    suspend fun importInto(uid: String, source: String, name: String, wordIds: List<Int>): ImportedDeck {
        val now = System.currentTimeMillis()
        val existing = dao.bySource(uid, source)
        if (existing != null) {
            val added = dao.append(existing.id, wordIds, now)
            return ImportedDeck(existing.key, existing.name, added = added, total = dao.wordIds(existing.id).size, isNew = false)
        }
        val clean = cleanDeckName(name) ?: "Imported deck"
        val deck = DeckEntity(uid = uid, name = clean, createdAt = now, source = source)
        dao.create(deck, wordIds, now)
        val count = wordIds.distinct().size
        return ImportedDeck(deck.key, clean, added = count, total = count, isNew = true)
    }
}

/** What an import did: the deck's key and name, how many words it added and how many the deck holds now. */
data class ImportedDeck(val key: String, val name: String, val added: Int, val total: Int, val isNew: Boolean)
