package com.flurai.app.core.profile

import com.flurai.app.core.prefs.UserKeys
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.flurai.app.core.model.JlptLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Study profiles in DataStore, keyed by Firebase uid. A missing profile means the user hasn't
 * finished onboarding. (Cloud sync of this comes in the sync milestone.)
 */
@Singleton
class StudyProfileRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    fun profile(uid: String): Flow<StudyProfile?> = dataStore.data.map { prefs ->
        val keys = Keys(uid)
        val level = prefs[keys.level]?.let { name -> JlptLevel.entries.find { it.name == name } }
            ?: return@map null
        StudyProfile(
            level = level,
            dailyMinutes = prefs[keys.dailyMinutes] ?: StudyProfile.DEFAULT_DAILY_MINUTES,
            newCardsPerDay = prefs[keys.newCards] ?: StudyProfile.DEFAULT_NEW_CARDS,
            knowsHiragana = prefs[keys.knowsHiragana] ?: true,
            knowsKatakana = prefs[keys.knowsKatakana] ?: true,
        )
    }.distinctUntilChanged()

    suspend fun save(uid: String, profile: StudyProfile) {
        val keys = Keys(uid)
        dataStore.edit { prefs ->
            prefs[keys.level] = profile.level.name
            prefs[keys.dailyMinutes] = profile.dailyMinutes
            prefs[keys.newCards] = profile.newCardsPerDay
            prefs[keys.knowsHiragana] = profile.knowsHiragana
            prefs[keys.knowsKatakana] = profile.knowsKatakana
        }
    }

    private class Keys(uid: String) {
        val level = UserKeys.string(uid, UserKeys.LEVEL)
        val dailyMinutes = UserKeys.int(uid, "dailyMinutes")
        val newCards = UserKeys.int(uid, "newCardsPerDay")
        val knowsHiragana = UserKeys.boolean(uid, "knowsHiragana")
        val knowsKatakana = UserKeys.boolean(uid, "knowsKatakana")
    }
}
