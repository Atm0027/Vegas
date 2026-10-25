/**
 * Vegas desktop: reads and writes the PC's own playlists and synced settings for the sync engine.
 * A row's local key is its own entry id; rows created from the phone reuse the sync entry id.
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.desktop.sync

import dev.alextorres.vegas.desktop.db.Library
import dev.alextorres.vegas.desktop.db.PlaylistEntity
import dev.alextorres.vegas.desktop.db.PlaylistEntryEntity
import dev.alextorres.vegas.desktop.db.SongEntity
import dev.alextorres.vegas.desktop.settings.AudioQualitySetting
import dev.alextorres.vegas.desktop.settings.DesktopSettings
import dev.alextorres.vegas.sync.AppliedChanges
import dev.alextorres.vegas.sync.LibraryAdapter
import dev.alextorres.vegas.sync.LibrarySnapshot
import dev.alextorres.vegas.sync.LocalEntry
import dev.alextorres.vegas.sync.LocalPlaylist
import dev.alextorres.vegas.sync.SongMeta

class DesktopLibraryAdapter(
    private val library: Library,
    private val settings: DesktopSettings,
    private val now: () -> Long = System::currentTimeMillis,
) : LibraryAdapter {
    private val dao = library.dao

    override suspend fun snapshot(): LibrarySnapshot {
        val entries = dao.allEntries().groupBy { it.playlistId }
        val playlists = dao.playlistRows().map { p ->
            LocalPlaylist(
                id = p.id,
                name = p.name,
                pinned = p.pinned,
                customOrder = p.customOrder,
                entries = entries[p.id].orEmpty().map { e ->
                    LocalEntry(e.entryId, SongMeta(e.song.id, e.song.title, e.song.artists, e.song.album, e.song.thumbnailUrl, e.song.durationMs))
                },
            )
        }
        val v = settings.flow.value
        return LibrarySnapshot(
            playlists,
            mapOf(
                AUDIO_QUALITY to v.audioQuality.name,
                HIDE_EXPLICIT to v.hideExplicit.toString(),
                HIDE_VIDEO_SONGS to v.hideVideoSongs.toString(),
                SKIP_SILENCE to v.skipSilence.toString(),
                PURE_BLACK to v.pureBlack.toString(),
            ),
        )
    }

    override suspend fun apply(changes: AppliedChanges): Map<String, String> {
        val created = mutableMapOf<String, String>()
        changes.deletedPlaylistIds.forEach { library.delete(it) }
        val existing = dao.playlistRows().associateBy { it.id }
        for (desired in changes.playlists) {
            val reg = desired.reg
            val old = existing[reg.id]
            dao.upsertPlaylist(
                PlaylistEntity(
                    id = reg.id,
                    name = reg.name,
                    createdAt = old?.createdAt ?: now(),
                    updatedAt = now(),
                    pinned = reg.pinned,
                    customOrder = reg.customOrder,
                ),
            )
            val keep = desired.entries.mapNotNull { it.localKey }.toSet()
            dao.deleteEntries(dao.entryRows(reg.id).map { it.entryId }.filter { it !in keep })
            dao.upsertSongs(desired.entries.map { it.song.toEntity() }.distinctBy { it.id })
            val newRows = mutableListOf<PlaylistEntryEntity>()
            desired.entries.forEachIndexed { i, e ->
                val key = (i + 1).toDouble()
                if (e.localKey != null) {
                    dao.setSortKey(e.localKey!!, key)
                } else {
                    newRows += PlaylistEntryEntity(e.entryId, reg.id, e.song.id, key, now())
                    created[e.entryId] = e.entryId
                }
            }
            if (newRows.isNotEmpty()) dao.insertEntries(newRows)
        }
        if (changes.settings.isNotEmpty()) settings.update { v -> changes.settings.entries.fold(v) { acc, (k, value) -> acc.with(k, value) } }
        return created
    }

    private fun DesktopSettings.Values.with(key: String, value: String): DesktopSettings.Values =
        when (key) {
            AUDIO_QUALITY -> runCatching { AudioQualitySetting.valueOf(value) }.getOrNull()?.let { copy(audioQuality = it) } ?: this
            HIDE_EXPLICIT -> copy(hideExplicit = value.toBoolean())
            HIDE_VIDEO_SONGS -> copy(hideVideoSongs = value.toBoolean())
            SKIP_SILENCE -> copy(skipSilence = value.toBoolean())
            PURE_BLACK -> copy(pureBlack = value.toBoolean())
            else -> this
        }

    private fun SongMeta.toEntity() = SongEntity(id, title, artists, album, thumbnailUrl, durationMs)

    companion object {
        const val AUDIO_QUALITY = "audioQuality"
        const val HIDE_EXPLICIT = "hideExplicit"
        const val HIDE_VIDEO_SONGS = "hideVideoSongs"
        const val SKIP_SILENCE = "skipSilence"
        const val PURE_BLACK = "pureBlack"
    }
}
