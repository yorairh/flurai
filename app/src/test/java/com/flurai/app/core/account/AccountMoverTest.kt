package com.flurai.app.core.account

import com.flurai.app.core.prefs.movePrefixedKeys
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccountMoverTest {
    @Test
    fun movesOnlyTheOldAccountsKeysAndOverwritesTheNewOnes() {
        val prefs = mutablePreferencesOf(
            stringPreferencesKey("old.level") to "N4",
            intPreferencesKey("old.newCardsPerDay") to 15,
            booleanPreferencesKey("old.reminderEnabled") to true,
            stringPreferencesKey("new.level") to "Beginner",
            stringPreferencesKey("other.level") to "N1",
        )
        movePrefixedKeys(prefs, "old", "new")
        assertEquals("N4", prefs[stringPreferencesKey("new.level")])
        assertEquals(15, prefs[intPreferencesKey("new.newCardsPerDay")])
        assertEquals(true, prefs[booleanPreferencesKey("new.reminderEnabled")])
        assertNull(prefs[stringPreferencesKey("old.level")])
        assertEquals("N1", prefs[stringPreferencesKey("other.level")])
    }

    @Test
    fun aPrefixThatIsPartOfAnotherUidIsNotTouched() {
        val prefs = mutablePreferencesOf(stringPreferencesKey("abc2.level") to "N3")
        movePrefixedKeys(prefs, "abc", "new")
        assertEquals("N3", prefs[stringPreferencesKey("abc2.level")])
    }
}
