package com.flurai.app.core.video

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** A channel, playlist or video the learner added themselves (UserDatabase v5). [level] is a JLPT number. */
@Entity(tableName = "custom_channel")
data class CustomChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uid: String,
    val name: String,
    val url: String,
    val level: Int,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

@Dao
interface CustomChannelDao {
    @Query("SELECT * FROM custom_channel WHERE uid = :uid ORDER BY added_at DESC")
    fun channels(uid: String): Flow<List<CustomChannelEntity>>

    @Insert
    suspend fun insert(channel: CustomChannelEntity)

    @Query("DELETE FROM custom_channel WHERE uid = :uid AND id = :id")
    suspend fun delete(uid: String, id: Long)
}

/**
 * Turns what the learner typed into a link to open: adds https:// when missing and accepts only
 * web links with a dotted host. Null when it is not a usable link.
 */
fun normalizeUrl(input: String): String? {
    val text = input.trim()
    if (text.isEmpty() || text.any { it.isWhitespace() }) return null
    val withScheme = if (text.startsWith("http://", true) || text.startsWith("https://", true)) text else "https://$text"
    val host = withScheme.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
    return if (host.contains('.') && !host.startsWith('.') && !host.endsWith('.')) withScheme else null
}
