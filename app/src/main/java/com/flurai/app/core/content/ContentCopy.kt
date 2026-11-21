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
internal const val DICTIONARY_DB_NAME = "dictionary.db"
internal const val DICTIONARY_STAMP_ASSET = "dictionary.db.stamp"
private const val PREFS = "content_db"
private const val KEY_STAMP = "stamp"
private const val KEY_DICTIONARY_STAMP = "dictionary_stamp"

/**
 * Whether the installed copy is out of date. No shipped stamp means nothing to compare with, so the
 * copy is kept; no saved stamp (first run, or an app from before stamps) copies once.
 */
fun isContentStale(installed: String?, shipped: String?): Boolean =
    shipped != null && installed != shipped

/**
 * Deletes the installed content.db when the shipped one has a new stamp. Call before opening Room.
 * Returns true when the copy was renewed (new content: words may have joined the built-in list).
 */
fun refreshContentCopy(context: Context): Boolean = refreshCopy(context, CONTENT_DB_NAME, STAMP_ASSET, KEY_STAMP)

/** The same for dictionary.db (the whole of JMdict, assets/dictionary.db.stamp). */
fun refreshDictionaryCopy(context: Context): Boolean =
    refreshCopy(context, DICTIONARY_DB_NAME, DICTIONARY_STAMP_ASSET, KEY_DICTIONARY_STAMP)

private fun refreshCopy(context: Context, dbName: String, stampAsset: String, key: String): Boolean {
    val shipped = runCatching {
        context.assets.open(stampAsset).bufferedReader().use { it.readText().trim() }
    }.getOrNull()?.ifEmpty { null }
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    if (!isContentStale(prefs.getString(key, null), shipped)) return false
    context.deleteDatabase(dbName)
    // commit(): the stamp must be saved before Room copies, or a crash in between repeats the copy at worst.
    prefs.edit().putString(key, shipped).commit()
    return true
}
