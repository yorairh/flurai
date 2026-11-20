package com.flurai.app.core.content

import android.content.Context

/**
 * Keeps the installed copy of content.db in step with the one shipped in the app.
 *
 * The content pipeline writes assets/content.db.stamp (a hash of content.db) next to the database.
 * When the stamp differs from the one saved at the last copy, the installed database is deleted and
 * Room copies the shipped one again. So a content change (a lesson, a word) only needs the pipeline
 * to be re-run; [ContentDatabase.version] goes up only when a table changes.
 */
internal const val CONTENT_DB_NAME = "content.db"
internal const val STAMP_ASSET = "content.db.stamp"
private const val PREFS = "content_db"
private const val KEY_STAMP = "stamp"

/**
 * Whether the installed copy is out of date. No shipped stamp means nothing to compare with, so the
 * copy is kept; no saved stamp (first run, or an app from before stamps) copies once.
 */
fun isContentStale(installed: String?, shipped: String?): Boolean =
    shipped != null && installed != shipped

/** Deletes the installed content.db when the shipped one has a new stamp. Call before opening Room. */
fun refreshContentCopy(context: Context) {
    val shipped = runCatching {
        context.assets.open(STAMP_ASSET).bufferedReader().use { it.readText().trim() }
    }.getOrNull()?.ifEmpty { null }
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    if (!isContentStale(prefs.getString(KEY_STAMP, null), shipped)) return
    context.deleteDatabase(CONTENT_DB_NAME)
    // commit(): the stamp must be saved before Room copies, or a crash in between repeats the copy at worst.
    prefs.edit().putString(KEY_STAMP, shipped).commit()
}
