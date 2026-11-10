package com.flurai.app.core.prefs

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
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The key scheme of the phone's settings file. Every account that ever used this phone keeps its
 * profile, settings and sync bookkeeping in one DataStore as "<uid>.<name>"; nothing outside this
 * file builds or takes apart such a key.
 */
object UserKeys {
    /** Sync cursors and hashes live under this name prefix and are never copied to the cloud. */
    private const val SYNC = "sync."

    private fun name(uid: String, name: String) = "$uid.$name"

    fun boolean(uid: String, name: String) = booleanPreferencesKey(name(uid, name))
    fun int(uid: String, name: String) = intPreferencesKey(name(uid, name))
    fun long(uid: String, name: String) = longPreferencesKey(name(uid, name))
    fun string(uid: String, name: String) = stringPreferencesKey(name(uid, name))

    /** The sync bookkeeping keys of [uid]. */
    internal fun sync(uid: String, name: String) = long(uid, "$SYNC$name")

    internal fun belongsTo(key: Preferences.Key<*>, uid: String) = key.name.startsWith("$uid.")
    internal fun isSync(key: Preferences.Key<*>, uid: String) = key.name.startsWith("$uid.$SYNC")
    internal fun shortName(key: Preferences.Key<*>, uid: String) = key.name.removePrefix("$uid.")

    /** The level key marks "has a profile" (see StudyProfileRepository). */
    const val LEVEL = "level"
}

/** Where a sync left off: the cloud time already merged, and the phone time already uploaded. */
data class SyncCursors(val pull: Long, val push: Long)

/**
 * Whole-account operations on the settings file: copy an account's settings to and from the cloud,
 * move them to another account, wipe them, and keep the sync cursors. The two repositories that
 * read and write single settings (SettingsRepository, StudyProfileRepository) use [UserKeys] directly.
 */
@Singleton
class UserPreferences @Inject constructor(private val dataStore: DataStore<Preferences>) {

    /** Whether [uid] has a profile on this phone (onboarding was done). */
    suspend fun hasProfile(uid: String): Boolean =
        dataStore.data.first().asMap().keys.any { it.name == "$uid.${UserKeys.LEVEL}" }

    /** Every account with a profile on this phone: its uid and the level it chose. */
    suspend fun levelsByUid(): Map<String, String> =
        dataStore.data.first().asMap().entries.mapNotNull { (key, value) ->
            key.name.takeIf { it.endsWith(".${UserKeys.LEVEL}") }?.removeSuffix(".${UserKeys.LEVEL}")?.let { it to value.toString() }
        }.toMap()

    // -- the cloud copy --------------------------------------------------------------------------

    /** Profile and settings of [uid] by name (without the uid), for the cloud copy. Sync bookkeeping is left out. */
    suspend fun export(uid: String): Map<String, Any> =
        dataStore.data.first().asMap().entries
            .filter { UserKeys.belongsTo(it.key, uid) && !UserKeys.isSync(it.key, uid) }
            .associate { UserKeys.shortName(it.key, uid) to it.value }

    /**
     * Writes values read back from the cloud. The cloud returns every whole number as a Long, and
     * every setting that is a number is an Int, so Longs become Ints.
     */
    suspend fun import(uid: String, values: Map<*, *>) {
        dataStore.edit { prefs ->
            values.forEach { (name, value) ->
                val key = "$name"
                when (value) {
                    is Boolean -> prefs[UserKeys.boolean(uid, key)] = value
                    is Long -> prefs[UserKeys.int(uid, key)] = value.toInt()
                    is Int -> prefs[UserKeys.int(uid, key)] = value
                    is String -> prefs[UserKeys.string(uid, key)] = value
                }
            }
        }
    }

    // -- accounts --------------------------------------------------------------------------------

    /** Moves [from]'s settings to [to] (replacing [to]'s); [to]'s sync cursors start over so everything is uploaded. */
    suspend fun move(from: String, to: String) {
        dataStore.edit { prefs ->
            movePrefixedKeys(prefs, from, to)
            prefs.remove(UserKeys.sync(to, "pull"))
            prefs.remove(UserKeys.sync(to, "push"))
        }
    }

    /** Deletes everything this phone holds for [uid]. */
    suspend fun wipe(uid: String) {
        dataStore.edit { prefs -> prefs.asMap().keys.filter { UserKeys.belongsTo(it, uid) }.forEach { prefs.remove(it) } }
    }

    // -- sync bookkeeping ------------------------------------------------------------------------

    suspend fun cursors(uid: String): SyncCursors {
        val prefs = dataStore.data.first()
        return SyncCursors(prefs[UserKeys.sync(uid, "pull")] ?: 0L, prefs[UserKeys.sync(uid, "push")] ?: 0L)
    }

    suspend fun saveCursors(uid: String, cursors: SyncCursors) {
        dataStore.edit {
            it[UserKeys.sync(uid, "pull")] = cursors.pull
            it[UserKeys.sync(uid, "push")] = cursors.push
        }
    }

    /** Makes the next sync upload everything. */
    suspend fun markAllDirty(uid: String) {
        dataStore.edit { it[UserKeys.sync(uid, "push")] = 0L }
    }

    /** A fingerprint of the small whole-document items (settings, channels, decks) as last uploaded. */
    suspend fun uploadedHash(uid: String): Long? = dataStore.data.first()[UserKeys.sync(uid, "meta")]

    suspend fun saveUploadedHash(uid: String, hash: Long) {
        dataStore.edit { it[UserKeys.sync(uid, "meta")] = hash }
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
