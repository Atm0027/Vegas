/**
 * Vegas desktop: local library. Every playlist entry has its own global id and a fractional sort key,
 * so a playlist can hold the same song twice and two devices can insert without colliding (sync, phase 3).
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.desktop.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.alextorres.vegas.desktop.playback.Track

@Entity(tableName = "song")
data class SongEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artists: String,
    val album: String?,
    val thumbnailUrl: String?,
    val durationMs: Long?,
) {
    fun toTrack() = Track(id, title, artists, album, thumbnailUrl, durationMs)
}

fun Track.toEntity() = SongEntity(id, title, artists, album, thumbnailUrl, durationMs)

@Entity(tableName = "playlist")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
    val customOrder: Int = 0,
)

@Entity(
    tableName = "playlist_entry",
    foreignKeys = [ForeignKey(PlaylistEntity::class, ["id"], ["playlistId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("playlistId", "sortKey"), Index("songId")],
)
data class PlaylistEntryEntity(
    @PrimaryKey val entryId: String,
    val playlistId: String,
    val songId: String,
    val sortKey: Double,
    val addedAt: Long,
)

data class PlaylistSummary(
    val id: String,
    val name: String,
    val pinned: Boolean,
    val customOrder: Int,
    val songCount: Int,
    val thumbnailUrl: String?,
)

data class EntryWithSong(
    val entryId: String,
    val playlistId: String,
    val sortKey: Double,
    @Embedded(prefix = "song_") val song: SongEntity,
)
