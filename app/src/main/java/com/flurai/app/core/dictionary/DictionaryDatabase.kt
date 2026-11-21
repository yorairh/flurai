package com.flurai.app.core.dictionary

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

/*
 * The whole of JMdict, shipped read-only in assets/dictionary.db and built by
 * tools/content/build_content.py from this exported schema (app/schemas). Like content.db it is
 * copied again when assets/dictionary.db.stamp changes (DictionaryCopy.kt); bump
 * DictionaryDatabase.version only when an entity changes.
 *
 * An entry's id is its JMdict sequence number (always >= 1,000,000), which is also the id cards and
 * decks store for a word that is not one of the built-in JLPT words (see WordSource).
 */

/** One JMdict entry, flattened for display: the usual spelling, its reading and the first senses. */
@Entity(tableName = "entry", indices = [Index("expression"), Index("reading")])
data class DictEntryEntity(
    @PrimaryKey val id: Int,
    val expression: String,
    val reading: String,
    /** Other spellings (kanji forms) separated by "; ". */
    @ColumnInfo(name = "other_forms") val otherForms: String?,
    /** The first senses: glosses joined by ", ", senses by "; ". */
    val meanings: String,
    /** JMdict part-of-speech codes of the first sense, comma-separated. */
    @ColumnInfo(name = "parts_of_speech") val partsOfSpeech: String?,
    /** Furigana markup with one reading per kanji, as in WordEntity.ruby; null for kana words. */
    val ruby: String?,
    /** JMdict marks one of its spellings as common (news/ichi/spec priority). */
    val common: Boolean,
)

/**
 * The spellings and readings of an entry other than its [DictEntryEntity.expression] and
 * [DictEntryEntity.reading] (kept out to save space), for lookups by form (search, import matching).
 */
@Entity(tableName = "form", primaryKeys = ["text", "entry_id"])
data class DictFormEntity(
    val text: String,
    @ColumnInfo(name = "entry_id") val entryId: Int,
    /** True for a kana reading, false for a kanji spelling. */
    @ColumnInfo(name = "is_kana") val isKana: Boolean,
)

/** English search over [DictEntryEntity.meanings]. */
@Fts4(contentEntity = DictEntryEntity::class)
@Entity(tableName = "entry_fts")
data class DictEntryFts(val meanings: String)

/** One example sentence for an entry (from Tatoeba, with furigana markup). */
@Entity(tableName = "example")
data class DictExampleEntity(
    @PrimaryKey @ColumnInfo(name = "entry_id") val entryId: Int,
    val japanese: String,
    val english: String,
    val ruby: String?,
)

@Entity(tableName = "dictionary_info")
data class DictInfoEntity(@PrimaryKey val key: String, val value: String)

@Database(
    entities = [DictEntryEntity::class, DictFormEntity::class, DictEntryFts::class, DictExampleEntity::class, DictInfoEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class DictionaryDatabase : RoomDatabase() {
    abstract fun dictionaryDao(): DictionaryDao

    companion object {
        const val ASSET_PATH = "dictionary.db"
    }
}

@Dao
interface DictionaryDao {
    @Query("SELECT * FROM entry WHERE id = :id")
    suspend fun entry(id: Int): DictEntryEntity?

    @Query("SELECT * FROM entry WHERE id IN (:ids)")
    suspend fun entries(ids: List<Int>): List<DictEntryEntity>

    @Query("SELECT * FROM example WHERE entry_id = :id")
    suspend fun example(id: Int): DictExampleEntity?

    /** Entries with a spelling or reading that is exactly [text]. */
    @Query(
        """
        SELECT * FROM entry WHERE id IN (
            SELECT id FROM entry WHERE expression = :text UNION SELECT id FROM entry WHERE reading = :text
            UNION SELECT entry_id FROM form WHERE text = :text
        ) ORDER BY common DESC, id
        """,
    )
    suspend fun entriesWithForm(text: String): List<DictEntryEntity>

    /** Entries that can be written [expression] and read [reading]. */
    @Query(
        """
        SELECT * FROM entry WHERE id IN (
            SELECT id FROM entry WHERE expression = :expression UNION SELECT id FROM entry WHERE reading = :expression
            UNION SELECT entry_id FROM form WHERE text = :expression
        ) AND id IN (
            SELECT id FROM entry WHERE reading = :reading UNION SELECT entry_id FROM form WHERE text = :reading AND is_kana = 1
        ) ORDER BY common DESC, id
        """,
    )
    suspend fun entriesWithFormAndReading(expression: String, reading: String): List<DictEntryEntity>

    /**
     * Search candidates: a spelling or reading that starts with or contains the query (or its kana), or a
     * meaning that matches the full-text query [match] (empty to skip). Common entries first; ranked later by rankWords.
     */
    @Query(
        """
        SELECT * FROM entry WHERE id IN (
            SELECT id FROM entry WHERE expression LIKE '%' || :query || '%' OR reading LIKE '%' || :query || '%'
                OR (:kana != '' AND (expression LIKE '%' || :kana || '%' OR reading LIKE '%' || :kana || '%'))
            UNION
            SELECT entry_id FROM form WHERE text LIKE '%' || :query || '%' OR (:kana != '' AND text LIKE '%' || :kana || '%')
            UNION
            SELECT rowid FROM entry_fts WHERE :match != '' AND entry_fts MATCH :match
        )
        ORDER BY common DESC, id LIMIT 400
        """,
    )
    suspend fun searchCandidates(query: String, kana: String, match: String): List<DictEntryEntity>
}
