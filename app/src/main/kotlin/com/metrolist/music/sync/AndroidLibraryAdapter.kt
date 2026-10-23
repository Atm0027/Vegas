/**
 * Vegas: reads and writes the phone's own playlists and synced settings for the sync with the PC.
 * Only library playlists made in Vegas (no YouTube browseId) take part; a row's local key is its map id.
 * Licensed under GPL-3.0
 */

package com.metrolist.music.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.metrolist.music.constants.AudioQualityKey
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.constants.PureBlackKey
import com.metrolist.music.constants.SkipSilenceKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.PlaylistSongMap
import com.metrolist.music.models.MediaMetadata
import dev.alextorres.vegas.sync.AppliedChanges
import dev.alextorres.vegas.sync.LibraryAdapter
import dev.alextorres.vegas.sync.LibrarySnapshot
import dev.alextorres.vegas.sync.LocalEntry
import dev.alextorres.vegas.sync.LocalPlaylist
import dev.alextorres.vegas.sync.SongMeta
import kotlinx.coroutines.flow.first
import java.time.LocalDateTime

class AndroidLibraryAdapter(
    private val database: MusicDatabase,
    private val dataStore: DataStore<Preferences>,
) : LibraryAdapter {
    override suspend fun snapshot(): LibrarySnapshot {
        val dao = database.syncDao
        val playlists = dao.syncablePlaylists()
            .map { p ->
                LocalPlaylist(
                    id = p.id,
                    name = p.name,
                    pinned = p.pinned,
                    customOrder = p.customOrder,
                    entries = dao.playlistRows(p.id).map { row ->
                        val song = row.song
                        LocalEntry(
                            row.map.id.toString(),
                            SongMeta(
                                id = song.id,
                                title = song.song.title,
                                artists = song.artists.joinToString(", ") { it.name },
                                album = song.song.albumName,
                                thumbnailUrl = song.song.thumbnailUrl,
                                durationMs = song.song.duration.takeIf { it > 0 }?.let { it * 1000L },
                            ),
                        )
                    },
                )
            }
        val prefs = dataStore.data.first()
        return LibrarySnapshot(
            playlists,
            mapOf(
                AUDIO_QUALITY to (prefs[AudioQualityKey] ?: "AUTO"),
                HIDE_EXPLICIT to (prefs[HideExplicitKey] ?: false).toString(),
                HIDE_VIDEO_SONGS to (prefs[HideVideoSongsKey] ?: false).toString(),
                SKIP_SILENCE to (prefs[SkipSilenceKey] ?: false).toString(),
                PURE_BLACK to (prefs[PureBlackKey] ?: false).toString(),
            ),
        )
    }

    override suspend fun apply(changes: AppliedChanges): Map<String, String> {
        val created = mutableMapOf<String, String>()
        val dao = database.syncDao
        changes.deletedPlaylistIds.forEach { id -> dao.playlistEntity(id)?.let { database.delete(it) } }
        for (desired in changes.playlists) {
            val reg = desired.reg
            val existing = dao.playlistEntity(reg.id)
            if (existing == null) {
                // bookmarkedAt is what makes a playlist show in the library.
                database.insert(PlaylistEntity(id = reg.id, name = reg.name, isLocal = true, pinned = reg.pinned, customOrder = reg.customOrder, bookmarkedAt = LocalDateTime.now()))
            } else {
                database.update(existing.copy(name = reg.name, pinned = reg.pinned, customOrder = reg.customOrder))
            }
            val keep = desired.entries.mapNotNull { it.localKey?.toIntOrNull() }.toSet()
            dao.playlistRows(reg.id).filter { it.map.id !in keep }.forEach { database.deletePlaylistSongMap(it.map.id) }
            desired.entries.forEachIndexed { position, e ->
                val mapId = e.localKey?.toIntOrNull()
                if (mapId != null) {
                    database.setPlaylistSongPosition(mapId, position)
                } else {
                    if (database.getSongByIdBlocking(e.song.id) == null) database.insert(e.song.toMediaMetadata())
                    val id = dao.insertMap(PlaylistSongMap(playlistId = reg.id, songId = e.song.id, position = position))
                    created[e.entryId] = id.toString()
                }
            }
        }
        if (changes.settings.isNotEmpty()) {
            dataStore.edit { prefs ->
                changes.settings.forEach { (key, value) ->
                    when (key) {
                        AUDIO_QUALITY -> prefs[AudioQualityKey] = value
                        HIDE_EXPLICIT -> prefs[HideExplicitKey] = value.toBoolean()
                        HIDE_VIDEO_SONGS -> prefs[HideVideoSongsKey] = value.toBoolean()
                        SKIP_SILENCE -> prefs[SkipSilenceKey] = value.toBoolean()
                        PURE_BLACK -> prefs[PureBlackKey] = value.toBoolean()
                    }
                }
            }
        }
        return created
    }

    private fun SongMeta.toMediaMetadata() =
        MediaMetadata(
            id = id,
            title = title,
            artists = artists.split(", ").filter { it.isNotBlank() }.map { MediaMetadata.Artist(id = null, name = it) },
            duration = durationMs?.let { (it / 1000).toInt() } ?: -1,
            thumbnailUrl = thumbnailUrl,
        )

    companion object {
        const val AUDIO_QUALITY = "audioQuality"
        const val HIDE_EXPLICIT = "hideExplicit"
        const val HIDE_VIDEO_SONGS = "hideVideoSongs"
        const val SKIP_SILENCE = "skipSilence"
        const val PURE_BLACK = "pureBlack"
    }
}
