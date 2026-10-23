/**
 * Vegas desktop: queries for the local library.
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.desktop.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Query(
        """
        SELECT p.id, p.name, p.pinned, p.customOrder,
            (SELECT COUNT(*) FROM playlist_entry e WHERE e.playlistId = p.id) AS songCount,
            (SELECT s.thumbnailUrl FROM playlist_entry e JOIN song s ON s.id = e.songId
                WHERE e.playlistId = p.id ORDER BY e.sortKey LIMIT 1) AS thumbnailUrl
        FROM playlist p
        ORDER BY p.pinned DESC, p.customOrder, p.name COLLATE NOCASE
        """,
    )
    fun playlists(): Flow<List<PlaylistSummary>>

    @Query(
        """
        SELECT e.entryId, e.playlistId, e.sortKey,
            s.id AS song_id, s.title AS song_title, s.artists AS song_artists, s.album AS song_album,
            s.thumbnailUrl AS song_thumbnailUrl, s.durationMs AS song_durationMs
        FROM playlist_entry e JOIN song s ON s.id = e.songId
        WHERE e.playlistId = :playlistId
        ORDER BY e.sortKey
        """,
    )
    fun entries(playlistId: String): Flow<List<EntryWithSong>>

    @Query("SELECT * FROM playlist_entry WHERE playlistId = :playlistId ORDER BY sortKey")
    suspend fun entryRows(playlistId: String): List<PlaylistEntryEntity>

    @Query("SELECT * FROM playlist")
    suspend fun playlistRows(): List<PlaylistEntity>

    @Query(
        """
        SELECT e.entryId, e.playlistId, e.sortKey,
            s.id AS song_id, s.title AS song_title, s.artists AS song_artists, s.album AS song_album,
            s.thumbnailUrl AS song_thumbnailUrl, s.durationMs AS song_durationMs
        FROM playlist_entry e JOIN song s ON s.id = e.songId
        ORDER BY e.playlistId, e.sortKey
        """,
    )
    suspend fun allEntries(): List<EntryWithSong>

    @Upsert
    suspend fun upsertPlaylist(playlist: PlaylistEntity)

    @Query("SELECT COALESCE(MAX(customOrder), -1) FROM playlist")
    suspend fun maxCustomOrder(): Int

    @Insert
    suspend fun insertPlaylist(playlist: PlaylistEntity)

    @Query("UPDATE playlist SET name = :name, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: String, name: String, now: Long)

    @Query("UPDATE playlist SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query("UPDATE playlist SET customOrder = :order WHERE id = :id")
    suspend fun setCustomOrder(id: String, order: Int)

    @Query("UPDATE playlist SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: String, now: Long)

    @Query("DELETE FROM playlist WHERE id = :id")
    suspend fun deletePlaylist(id: String)

    @Upsert
    suspend fun upsertSongs(songs: List<SongEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEntries(entries: List<PlaylistEntryEntity>)

    @Query("DELETE FROM playlist_entry WHERE entryId IN (:entryIds)")
    suspend fun deleteEntries(entryIds: List<String>)

    @Query("UPDATE playlist_entry SET sortKey = :sortKey WHERE entryId = :entryId")
    suspend fun setSortKey(entryId: String, sortKey: Double)

    @Transaction
    suspend fun reorderPlaylists(idsInOrder: List<String>) {
        idsInOrder.forEachIndexed { i, id -> setCustomOrder(id, i) }
    }
}
