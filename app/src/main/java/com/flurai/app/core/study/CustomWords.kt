package com.flurai.app.core.study

import androidx.room.ColumnInfo
import androidx.room.Entity

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
