/**
 * Vegas desktop: tables of the offline mode. A song is downloaded when it has a `download` row and
 * its file exists. Lists marked for offline live in their own table so sync never touches them
 * (each device decides what it downloads).
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.desktop.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "download")
data class DownloadEntity(
    @PrimaryKey val songId: String,
    /** Absolute path of the audio file; it stays valid if the download folder setting changes. */
    val path: String,
    val size: Long,
    val mimeType: String,
    val addedAt: Long,
)

@Entity(
    tableName = "offline_playlist",
    foreignKeys = [ForeignKey(PlaylistEntity::class, ["id"], ["playlistId"], onDelete = ForeignKey.CASCADE)],
)
data class OfflinePlaylistEntity(
    @PrimaryKey val playlistId: String,
    val markedAt: Long,
)

/** A single song asked for by hand; the row goes away once it is downloaded. */
@Entity(tableName = "download_request")
data class DownloadRequestEntity(
    @PrimaryKey val songId: String,
    val addedAt: Long,
)

@Dao
interface OfflineDao {
    /** Songs of marked lists plus single requests, minus what is already downloaded. */
    @Query(
        """
        SELECT * FROM song WHERE id IN (
            SELECT e.songId FROM playlist_entry e JOIN offline_playlist o ON o.playlistId = e.playlistId
            UNION SELECT songId FROM download_request
        ) AND id NOT IN (SELECT songId FROM download)
        ORDER BY id
        """,
    )
    fun pending(): Flow<List<SongEntity>>

    @Query("SELECT * FROM download")
    fun downloads(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM download")
    suspend fun downloadRows(): List<DownloadEntity>

    @Query("SELECT * FROM download WHERE songId = :songId")
    suspend fun download(songId: String): DownloadEntity?

    @Upsert
    suspend fun upsertDownload(download: DownloadEntity)

    @Query("DELETE FROM download WHERE songId IN (:songIds)")
    suspend fun deleteDownloads(songIds: List<String>)

    @Query("SELECT playlistId FROM offline_playlist")
    fun offlinePlaylistIds(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun markPlaylist(playlist: OfflinePlaylistEntity)

    @Query("DELETE FROM offline_playlist WHERE playlistId = :playlistId")
    suspend fun unmarkPlaylist(playlistId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun request(request: DownloadRequestEntity)

    @Query("DELETE FROM download_request WHERE songId IN (:songIds)")
    suspend fun deleteRequests(songIds: List<String>)

    @Query("SELECT DISTINCT songId FROM playlist_entry WHERE playlistId = :playlistId")
    suspend fun songIdsIn(playlistId: String): List<String>

    @Query(
        """
        SELECT DISTINCT e.songId FROM playlist_entry e JOIN offline_playlist o ON o.playlistId = e.playlistId
        WHERE e.playlistId != :playlistId
        """,
    )
    suspend fun songIdsInOtherOfflinePlaylists(playlistId: String): List<String>

    /** The name of a marked list holding the song, which would download it again. */
    @Query(
        """
        SELECT p.name FROM playlist p JOIN offline_playlist o ON o.playlistId = p.id
        JOIN playlist_entry e ON e.playlistId = p.id
        WHERE e.songId = :songId ORDER BY p.name LIMIT 1
        """,
    )
    suspend fun offlinePlaylistHolding(songId: String): String?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSongIfMissing(song: SongEntity)

    @Query("SELECT * FROM song WHERE id = :songId")
    suspend fun song(songId: String): SongEntity?
}
