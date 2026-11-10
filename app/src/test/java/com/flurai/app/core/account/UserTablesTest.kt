package com.flurai.app.core.account

import com.flurai.app.core.sync.CLOUD_COLLECTIONS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards "I added a table and forgot ...": every table of the user database must be registered in
 * [UserTables], wiped and moved by [AccountDao], and the cloud collections must be the ones the
 * registry names. Room's SQL cannot run in a unit test, so the account queries are checked as
 * source text; the failure messages say what to add.
 */
class UserTablesTest {
    // Unit tests run in the app module's folder.
    private val accountSource = File("src/main/java/com/flurai/app/core/account/AccountMover.kt").readText()

    /** Table names of the newest exported UserDatabase schema (Room writes it on every build). */
    private fun schemaTables(): Set<String> {
        val newest = File("schemas/com.flurai.app.core.study.UserDatabase").listFiles { f -> f.extension == "json" }!!
            .maxBy { it.nameWithoutExtension.toInt() }
        return Regex("\"tableName\"\\s*:\\s*\"([a-z_]+)\"").findAll(newest.readText()).map { it.groupValues[1] }.toSet()
    }

    @Test
    fun everyTableOfTheUserDatabaseIsRegistered() {
        val registered = UserTables.all.map { it.name }.toSet()
        assertEquals(
            "UserDatabase has tables that are not in UserTables (or the other way round). Register the table in " +
                "UserTables.kt, then make AccountDao wipe and move it and decide whether sync backs it up.",
            schemaTables(), registered,
        )
    }

    @Test
    fun accountDaoWipesEveryTable() {
        UserTables.all.forEach {
            assertTrue("AccountDao.wipe does not delete from ${it.name}: add a DELETE query and call it in wipe()", Regex("DELETE FROM ${it.name}" + "\\b").containsMatchIn(accountSource))
        }
    }

    @Test
    fun accountDaoMovesEveryTableThatHasAUid() {
        UserTables.all.filter { it.ownedBy == null }.forEach {
            assertTrue(
                "AccountDao.move does not move ${it.name} to the new account: add an UPDATE ... SET uid query and call it in move()",
                Regex("UPDATE( OR IGNORE)? ${it.name} SET uid").containsMatchIn(accountSource),
            )
        }
        // A table owned by another has no uid; it must be reached through its owner's rows.
        UserTables.all.filter { it.ownedBy != null }.forEach { child ->
            assertTrue("${child.name} says it is owned by ${child.ownedBy}, which is not registered", UserTables.all.any { it.name == child.ownedBy })
            assertTrue("${child.name} is wiped through ${child.ownedBy}'s rows", "${child.ownedBy} WHERE uid" in accountSource)
        }
    }

    @Test
    fun theCloudCollectionsAreExactlyTheOnesTheRegistryNames() {
        assertEquals(
            "Sync's collections (SyncCodec.kt) and the cloud column of UserTables disagree",
            UserTables.cloudCollections, CLOUD_COLLECTIONS.toSet(),
        )
    }

    @Test
    fun theEraseTextNamesEveryTableTheLearnerKnows() {
        val text = UserTables.erasedSummary()
        assertTrue(text.startsWith("flashcards, "))
        assertTrue("decks" in text && "channels you added" in text)
        assertTrue(text.endsWith(", plus your level and settings"))
    }
}
