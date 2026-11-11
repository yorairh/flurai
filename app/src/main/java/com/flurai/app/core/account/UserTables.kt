package com.flurai.app.core.account

/**
 * One table of the user database (UserDatabase), and everything that has to know about it.
 *
 * Adding a per-user table means adding it here too: `UserTablesTest` fails until the table is
 * registered, until [AccountDao] wipes it (and moves it between accounts), and until sync's cloud
 * collections match [cloud]. The text shown before an account or guest profile is erased is built
 * from [label].
 */
class UserTable(
    val name: String,
    /** What the learner calls it in "this deletes your ...", or null for a table that is part of another. */
    val label: String?,
    /** The cloud collection it is backed up in (see SyncEngine), or null when it is not backed up. */
    val cloud: String?,
    /** A table without a uid column is moved and wiped through the table that owns it. */
    val ownedBy: String? = null,
)

object UserTables {
    val all = listOf(
        UserTable("card", "flashcards", cloud = "cards"),
        UserTable("review_log", "review history", cloud = null), // nothing reads it, so it is not backed up
        UserTable("study_day", "streaks and study time", cloud = "days"),
        UserTable("test_result", "test results", cloud = "tests"),
        UserTable("grammar_progress", "grammar progress", cloud = "grammar"),
        UserTable("custom_channel", "channels you added", cloud = "meta"),
        UserTable("deck", "decks", cloud = "meta"),
        UserTable("deck_word", null, cloud = "meta", ownedBy = "deck"),
    )

    /** The cloud collections that hold a user's backup. */
    val cloudCollections: Set<String> = all.mapNotNull { it.cloud }.toSet()

    /** "flashcards, review history, ..., plus your level and settings": what an erase takes with it. */
    fun erasedSummary(): String = all.mapNotNull { it.label }.joinToString(", ") + ", plus your level and settings"
}
