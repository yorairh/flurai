package com.flurai.app.core.account

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class UidCount(val uid: String, val count: Int)

/** Moves one account's local rows to another account (all user tables). */
@Dao
interface AccountDao {
    @Query("SELECT uid, COUNT(*) AS count FROM card GROUP BY uid")
    suspend fun cardCounts(): List<UidCount>

    @Query("UPDATE OR IGNORE card SET uid = :to WHERE uid = :from")
    suspend fun moveCards(from: String, to: String)

    @Query("DELETE FROM card WHERE uid = :from")
    suspend fun dropCards(from: String)

    @Query("UPDATE review_log SET uid = :to WHERE uid = :from")
    suspend fun moveLogs(from: String, to: String)

    /** Days studied on both accounts keep their larger totals. */
    @Query(
        """
        UPDATE study_day SET
            seconds = MAX(seconds, (SELECT o.seconds FROM study_day o WHERE o.uid = :from AND o.day = study_day.day)),
            reviews = MAX(reviews, (SELECT o.reviews FROM study_day o WHERE o.uid = :from AND o.day = study_day.day))
        WHERE uid = :to AND day IN (SELECT day FROM study_day WHERE uid = :from)
        """,
    )
    suspend fun mergeDays(from: String, to: String)

    @Query("UPDATE OR IGNORE study_day SET uid = :to WHERE uid = :from")
    suspend fun moveDays(from: String, to: String)

    @Query("DELETE FROM study_day WHERE uid = :from")
    suspend fun dropDays(from: String)

    @Query("UPDATE test_result SET uid = :to WHERE uid = :from")
    suspend fun moveTests(from: String, to: String)

    @Query("UPDATE OR IGNORE grammar_progress SET uid = :to WHERE uid = :from")
    suspend fun moveGrammar(from: String, to: String)

    @Query("DELETE FROM grammar_progress WHERE uid = :from")
    suspend fun dropGrammar(from: String)

    @Query("UPDATE custom_channel SET uid = :to WHERE uid = :from")
    suspend fun moveChannels(from: String, to: String)

    /** Decks keep their ids, so their words follow without being touched. */
    @Query("UPDATE deck SET uid = :to WHERE uid = :from")
    suspend fun moveDecks(from: String, to: String)

    @Query("DELETE FROM deck_word WHERE deck_id IN (SELECT id FROM deck WHERE uid = :uid)")
    suspend fun dropDeckWords(uid: String)

    @Query("DELETE FROM deck WHERE uid = :uid")
    suspend fun dropDeckRows(uid: String)

    @Query("DELETE FROM review_log WHERE uid = :uid")
    suspend fun dropLogs(uid: String)

    @Query("DELETE FROM test_result WHERE uid = :uid")
    suspend fun dropTests(uid: String)

    @Query("DELETE FROM custom_channel WHERE uid = :uid")
    suspend fun dropChannels(uid: String)

    /** Removes every row of [uid] (account deletion). */
    @Transaction
    suspend fun wipe(uid: String) {
        dropCards(uid); dropLogs(uid); dropDays(uid); dropTests(uid); dropGrammar(uid); dropChannels(uid)
        dropDeckWords(uid); dropDeckRows(uid)
    }

    /**
     * Everything of [from] becomes [to]'s. Where both have the same card or grammar point, [to]'s
     * own copy is kept.
     */
    @Transaction
    suspend fun move(from: String, to: String) {
        moveCards(from, to); dropCards(from)
        moveLogs(from, to)
        mergeDays(from, to); moveDays(from, to); dropDays(from)
        moveTests(from, to)
        moveGrammar(from, to); dropGrammar(from)
        moveChannels(from, to)
        moveDecks(from, to)
    }
}

/** Progress on this phone that belongs to a different account than the signed-in one. */
data class OtherProgress(val uid: String, val cards: Int, val level: String?)

/**
 * Finds and moves study data left on this phone by another account (for example one created with
 * a mistyped email). Settings and the study profile are stored as "<uid>.<name>" keys in DataStore.
 */
@Singleton
class AccountMover @Inject constructor(
    private val dao: AccountDao,
    private val dataStore: DataStore<Preferences>,
) {
    suspend fun otherProgress(currentUid: String): List<OtherProgress> {
        val prefs = dataStore.data.first().asMap()
        val levels = prefs.entries.mapNotNull { (key, value) ->
            val name = key.name
            if (name.endsWith(".level")) name.removeSuffix(".level") to value.toString() else null
        }.toMap()
        val cards = dao.cardCounts().associate { it.uid to it.count }
        return (levels.keys + cards.keys).filter { it != currentUid }.distinct()
            .map { OtherProgress(it, cards[it] ?: 0, levels[it]) }
            .filter { it.cards > 0 || it.level != null }
            .sortedByDescending { it.cards }
    }

    /** Deletes everything this phone holds for [uid]: database rows and "<uid>." settings. */
    suspend fun wipe(uid: String) {
        dao.wipe(uid)
        dataStore.edit { prefs ->
            prefs.asMap().keys.filter { it.name.startsWith("$uid.") }.forEach { prefs.remove(it) }
        }
    }

    /** Moves [from]'s data to [to]; [to]'s profile and settings are replaced by [from]'s. */
    suspend fun move(from: String, to: String) {
        dao.move(from, to)
        dataStore.edit { prefs ->
            movePrefixedKeys(prefs, from, to)
            // The moved rows must all be uploaded under the new account, so its sync cursors start over.
            prefs.remove(androidx.datastore.preferences.core.longPreferencesKey("$to.sync.pull"))
            prefs.remove(androidx.datastore.preferences.core.longPreferencesKey("$to.sync.push"))
        }
    }
}

/** Copies every "<from>.x" key to "<to>.x" (overwriting) and removes the originals. */
internal fun movePrefixedKeys(prefs: MutablePreferences, from: String, to: String) {
    val prefix = "$from."
    prefs.asMap().entries.filter { it.key.name.startsWith(prefix) }.forEach { (key, value) ->
        val name = to + "." + key.name.removePrefix(prefix)
        @Suppress("UNCHECKED_CAST")
        when (value) {
            is Boolean -> prefs[booleanPreferencesKey(name)] = value
            is Int -> prefs[intPreferencesKey(name)] = value
            is Long -> prefs[longPreferencesKey(name)] = value
            is Float -> prefs[floatPreferencesKey(name)] = value
            is Double -> prefs[doublePreferencesKey(name)] = value
            is String -> prefs[stringPreferencesKey(name)] = value
            is Set<*> -> prefs[stringSetPreferencesKey(name)] = value as Set<String>
        }
        prefs.remove(key)
    }
}
