/**
 * Vegas: tables of the home-network sync with the PC (registers, row bindings, counters).
 * Same layout as the PC app's; only new tables, the library tables are untouched.
 * Licensed under GPL-3.0
 */

package com.metrolist.music.sync

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.PlaylistSong
import com.metrolist.music.db.entities.PlaylistSongMap

@Entity(tableName = "sync_playlist")
data class SyncPlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val pinned: Boolean,
    val customOrder: Int,
    val deleted: Boolean,
    val hlcWall: Long,
    val hlcCounter: Int,
    val hlcDevice: String,
    val seq: Long,
)

@Entity(tableName = "sync_entry", indices = [Index("seq")])
data class SyncEntryEntity(
    @PrimaryKey val entryId: String,
    val playlistId: String,
    /** The song's metadata as JSON (SongMeta). */
    val song: String,
    val sortKey: Double,
    val deleted: Boolean,
    val hlcWall: Long,
    val hlcCounter: Int,
    val hlcDevice: String,
    val seq: Long,
)

@Entity(tableName = "sync_setting")
data class SyncSettingEntity(
    @PrimaryKey val key: String,
    val value: String,
    val hlcWall: Long,
    val hlcCounter: Int,
    val hlcDevice: String,
    val seq: Long,
)

@Entity(tableName = "sync_binding", indices = [Index(value = ["localKey"], unique = true)])
data class SyncBindingEntity(
    @PrimaryKey val entryId: String,
    val localKey: String,
)

@Entity(tableName = "sync_meta")
data class SyncMetaEntity(
    @PrimaryKey val key: String,
    val value: Long,
)

@Dao
interface SyncDao {
    @Query("SELECT * FROM sync_playlist")
    suspend fun playlists(): List<SyncPlaylistEntity>

    @Query("SELECT * FROM sync_entry")
    suspend fun entries(): List<SyncEntryEntity>

    @Query("SELECT * FROM sync_setting")
    suspend fun settings(): List<SyncSettingEntity>

    @Query("SELECT * FROM sync_playlist WHERE seq > :seq")
    suspend fun playlistsSince(seq: Long): List<SyncPlaylistEntity>

    @Query("SELECT * FROM sync_entry WHERE seq > :seq")
    suspend fun entriesSince(seq: Long): List<SyncEntryEntity>

    @Query("SELECT * FROM sync_setting WHERE seq > :seq")
    suspend fun settingsSince(seq: Long): List<SyncSettingEntity>

    @Upsert
    suspend fun put(playlist: SyncPlaylistEntity)

    @Upsert
    suspend fun put(entry: SyncEntryEntity)

    @Upsert
    suspend fun put(setting: SyncSettingEntity)

    @Query("SELECT entryId FROM sync_binding WHERE localKey = :localKey")
    suspend fun entryIdFor(localKey: String): String?

    @Query("SELECT localKey FROM sync_binding WHERE entryId = :entryId")
    suspend fun localKeyFor(entryId: String): String?

    @Query("DELETE FROM sync_binding WHERE entryId = :entryId OR localKey = :localKey")
    suspend fun clearBinding(entryId: String, localKey: String)

    @Upsert
    suspend fun put(binding: SyncBindingEntity)

    @Query("DELETE FROM sync_binding WHERE entryId = :entryId")
    suspend fun unbind(entryId: String)

    @Query("SELECT value FROM sync_meta WHERE `key` = :key")
    suspend fun meta(key: String): Long?

    @Upsert
    suspend fun put(meta: SyncMetaEntity)

    /** Inserts a playlist row and returns its id (the row's local key for sync). */
    @Insert
    fun insertMap(map: PlaylistSongMap): Long

    // Plain (non-Flow) reads so they run on the sync transaction's own connection.
    @Query("SELECT * FROM playlist WHERE bookmarkedAt IS NOT NULL AND browseId IS NULL AND isEditable = 1 ORDER BY rowId")
    suspend fun syncablePlaylists(): List<PlaylistEntity>

    @Query("SELECT * FROM playlist WHERE id = :id")
    suspend fun playlistEntity(id: String): PlaylistEntity?

    @Transaction
    @Query("SELECT * FROM playlist_song_map WHERE playlistId = :playlistId ORDER BY position")
    suspend fun playlistRows(playlistId: String): List<PlaylistSong>
}
