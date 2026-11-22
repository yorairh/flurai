package com.flurai.app.core.study

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert

/**
 * A word or sentence of the learner's own that is not in the dictionary (UserDatabase v8), such as an
 * Anki card that matched no JMdict entry. Its id is negative, so it never clashes with built-in
 * (1..999,999) or dictionary (>= 1,000,000) word ids; cards and decks refer to it like any word.
 */
@Entity(tableName = "custom_word", primaryKeys = ["uid", "id"])
data class CustomWordEntity(
    val uid: String,
    val id: Int,
    /** [KIND_WORD] or [KIND_SENTENCE] (a sentence never gets a Production card). */
    val kind: String,
    val expression: String,
    val reading: String,
    val meaning: String,
    /** The Anki note it came from, so a re-import finds it again. */
    @ColumnInfo(name = "anki_guid") val ankiGuid: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    companion object {
        const val KIND_WORD = "word"
        const val KIND_SENTENCE = "sentence"
    }
}

@Dao
interface CustomWordDao {
    /**
     * A custom word by id, whichever account holds it: the id comes from the word's text, so two accounts
     * with the same id hold the same word (a clash is moved to another id when it is made).
     */
    @Query("SELECT * FROM custom_word WHERE id = :id LIMIT 1")
    suspend fun anyById(id: Int): CustomWordEntity?

    @Query("SELECT * FROM custom_word WHERE id IN (:ids) GROUP BY id")
    suspend fun anyByIds(ids: List<Int>): List<CustomWordEntity>

    @Query("SELECT * FROM custom_word WHERE uid = :uid AND id = :id")
    suspend fun get(uid: String, id: Int): CustomWordEntity?

    @Query("SELECT * FROM custom_word WHERE uid = :uid AND updated_at > :since")
    suspend fun since(uid: String, since: Long): List<CustomWordEntity>

    @Upsert
    suspend fun upsert(word: CustomWordEntity)

    @Upsert
    suspend fun upsertAll(words: List<CustomWordEntity>)

    @Query("SELECT EXISTS(SELECT 1 FROM custom_word WHERE id = :id AND kind = 'sentence')")
    suspend fun isSentence(id: Int): Boolean
}
