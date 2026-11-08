package com.flurai.app.core.profile

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.flurai.app.core.model.JlptLevel
import kotlinx.coroutines.flow.Flow
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
    }

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
        val level = stringPreferencesKey("$uid.level")
        val dailyMinutes = intPreferencesKey("$uid.dailyMinutes")
        val newCards = intPreferencesKey("$uid.newCardsPerDay")
        val knowsHiragana = booleanPreferencesKey("$uid.knowsHiragana")
        val knowsKatakana = booleanPreferencesKey("$uid.knowsKatakana")
    }
}
