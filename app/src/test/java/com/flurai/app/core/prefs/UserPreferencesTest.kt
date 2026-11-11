package com.flurai.app.core.prefs

import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The settings file seen account by account, as sync, account moves and deletion use it. */
class UserPreferencesTest {
    private val store = InMemoryPrefs()
    private val prefs = UserPreferences(store)

    private suspend fun seed(uid: String, level: String = "N5") = store.edit {
        it[UserKeys.string(uid, UserKeys.LEVEL)] = level
        it[UserKeys.int(uid, "newCardsPerDay")] = 12
        it[UserKeys.boolean(uid, "showFurigana")] = false
    }

    private suspend fun keys() = store.data.first().asMap().keys.map { it.name }.toSet()

    @Test
    fun exportListsOneAccountsSettingsWithoutTheUidAndWithoutSyncBookkeeping() = runTest {
        seed("a"); seed("b", "N2")
        prefs.saveCursors("a", SyncCursors(5, 6))
        prefs.saveUploadedHash("a", "decks", 99)

        assertEquals(mapOf("level" to "N5", "newCardsPerDay" to 12, "showFurigana" to false), prefs.export("a"))
    }

    @Test
    fun aSettingComesBackFromTheCloudAsTheSameTypeEvenThoughNumbersArriveAsLongs() = runTest {
        seed("a")
        val exported = prefs.export("a")
        // The cloud hands every whole number back as a Long.
        val fromCloud = exported.mapValues { (_, v) -> if (v is Int) v.toLong() else v }

        prefs.import("new", fromCloud)

        assertEquals(exported, prefs.export("new"))
    }

    @Test
    fun hasProfileMeansTheLevelWasChosen() = runTest {
        assertFalse(prefs.hasProfile("a"))
        store.edit { it[UserKeys.int("a", "newCardsPerDay")] = 5 }
        assertFalse(prefs.hasProfile("a"))
        seed("a")
        assertTrue(prefs.hasProfile("a"))
        assertFalse(prefs.hasProfile("ab")) // another account whose uid merely starts the same
    }

    @Test
    fun everyAccountWithAProfileIsListedWithItsLevel() = runTest {
        seed("a"); seed("b", "N2")
        store.edit { it[UserKeys.int("c", "newCardsPerDay")] = 1 } // no profile

        assertEquals(mapOf("a" to "N5", "b" to "N2"), prefs.levelsByUid())
    }

    @Test
    fun movingReplacesTheTargetsSettingsAndRestartsItsSyncCursors() = runTest {
        seed("old", "N3"); seed("new", "N5")
        prefs.saveCursors("new", SyncCursors(70, 80))

        prefs.move("old", "new")

        assertEquals("N3", prefs.export("new")["level"])
        assertEquals(SyncCursors(0, 0), prefs.cursors("new")) // everything is uploaded again
        assertTrue(keys().none { it.startsWith("old.") })
    }

    @Test
    fun movingAnAccountLeavesOthersWhoseUidStartsTheSameAlone() = runTest {
        seed("abc"); seed("abcd", "N1")

        prefs.move("abc", "target")

        assertEquals("N1", prefs.export("abcd")["level"])
        assertEquals("N5", prefs.export("target")["level"])
    }

    @Test
    fun wipingRemovesOnlyThatAccountsKeys() = runTest {
        seed("a"); seed("ab", "N4")
        prefs.saveCursors("a", SyncCursors(1, 2))

        prefs.wipe("a")

        assertTrue(keys().none { it.startsWith("a.") })
        assertEquals("N4", prefs.export("ab")["level"])
    }

    @Test
    fun syncCursorsStartAtZeroAndMarkAllDirtyOnlyResetsTheUploadOne() = runTest {
        assertEquals(SyncCursors(0, 0), prefs.cursors("a"))
        prefs.saveCursors("a", SyncCursors(11, 22))
        assertEquals(SyncCursors(11, 22), prefs.cursors("a"))

        prefs.markAllDirty("a")

        assertEquals(SyncCursors(11, 0), prefs.cursors("a"))
    }

    @Test
    fun theUploadedHashIsRememberedPerAccountAndPart() = runTest {
        assertNull(prefs.uploadedHash("a", "decks"))
        prefs.saveUploadedHash("a", "decks", 1234)
        assertEquals(1234L, prefs.uploadedHash("a", "decks"))
        assertNull(prefs.uploadedHash("a", "channels"))
        assertNull(prefs.uploadedHash("b", "decks"))
    }
}
