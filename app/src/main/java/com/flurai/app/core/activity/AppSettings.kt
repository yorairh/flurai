package com.flurai.app.core.activity

import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.flurai.app.core.prefs.UserKeys
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class AppSettings(
    val reminderEnabled: Boolean = false,
    val reminderHour: Int = 19,
    val reminderMinute: Int = 0,
    /** Minutes of study that make a day count toward the streak. */
    val streakMinutes: Int = 5,
    /** Small kana readings above kanji in the Reading screen. */
    val showFurigana: Boolean = true,
    /** Hear-the-word cards for words you have learned. */
    val listeningCards: Boolean = true,
) {
    companion object { val StreakChoices = listOf(1, 5, 10, 15, 20, 30) }
}

@Singleton
class SettingsRepository @Inject constructor(private val dataStore: DataStore<Preferences>) {
    fun settings(uid: String): Flow<AppSettings> = dataStore.data.map { read(it, Keys(uid)) }
        // Every DataStore write (sync cursors, other settings) re-emits; pass on only real changes.
        .distinctUntilChanged()

    suspend fun update(uid: String, change: (AppSettings) -> AppSettings) {
        dataStore.edit { prefs ->
            val k = Keys(uid)
            val new = change(read(prefs, k))
            prefs[k.enabled] = new.reminderEnabled
            prefs[k.hour] = new.reminderHour
            prefs[k.minute] = new.reminderMinute
            prefs[k.streak] = new.streakMinutes
            prefs[k.furigana] = new.showFurigana
            prefs[k.listening] = new.listeningCards
        }
    }

    private fun read(prefs: Preferences, k: Keys): AppSettings {
        val d = AppSettings()
        return AppSettings(
            reminderEnabled = prefs[k.enabled] ?: d.reminderEnabled,
            reminderHour = prefs[k.hour] ?: d.reminderHour,
            reminderMinute = prefs[k.minute] ?: d.reminderMinute,
            streakMinutes = prefs[k.streak] ?: d.streakMinutes,
            showFurigana = prefs[k.furigana] ?: d.showFurigana,
            listeningCards = prefs[k.listening] ?: d.listeningCards,
        )
    }

    private class Keys(uid: String) {
        val enabled = UserKeys.boolean(uid, "reminderEnabled")
        val hour = UserKeys.int(uid, "reminderHour")
        val minute = UserKeys.int(uid, "reminderMinute")
        val streak = UserKeys.int(uid, "streakMinutes")
        val furigana = UserKeys.boolean(uid, "showFurigana")
        val listening = UserKeys.boolean(uid, "listeningCards")
    }
}
