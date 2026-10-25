/**
 * Vegas desktop: playlist operations, with the same rules as the phone (pinned first, duplicate
 * warning on add, «Quitar de la lista» removes one entry).
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.desktop.db

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import dev.alextorres.vegas.desktop.playback.Track
import kotlinx.coroutines.flow.Flow
import java.util.UUID

data class AddResult(val added: Int, val duplicates: List<Track>)

class Library(
    private val db: VegasDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) {
    internal val dao = db.library()

    fun playlists(): Flow<List<PlaylistSummary>> = dao.playlists()

    fun entries(playlistId: String): Flow<List<EntryWithSong>> = dao.entries(playlistId)

    suspend fun createPlaylist(name: String): String =
        write {
            val id = UUID.randomUUID().toString()
            val t = now()
            dao.insertPlaylist(PlaylistEntity(id, name, t, t, customOrder = dao.maxCustomOrder() + 1))
            id
        }

    suspend fun rename(id: String, name: String) = dao.rename(id, name, now())

    suspend fun delete(id: String) = dao.deletePlaylist(id)

    suspend fun setPinned(id: String, pinned: Boolean) = dao.setPinned(id, pinned)

    suspend fun reorderPlaylists(idsInOrder: List<String>) = dao.reorderPlaylists(idsInOrder)

    suspend fun addTracks(playlistId: String, tracks: List<Track>, force: Boolean = false): AddResult =
        write {
            val rows = dao.entryRows(playlistId)
            val present = rows.mapTo(HashSet()) { it.songId }
            val duplicates = tracks.filter { it.id in present }
            val toAdd = if (force) tracks else tracks.filter { it.id !in present }.distinctBy { it.id }
            if (toAdd.isNotEmpty()) {
                dao.upsertSongs(toAdd.map { it.toEntity() })
                val start = (rows.lastOrNull()?.sortKey ?: 0.0) + 1.0
                val t = now()
                dao.insertEntries(
                    toAdd.mapIndexed { i, track ->
                        PlaylistEntryEntity(UUID.randomUUID().toString(), playlistId, track.id, start + i, t)
                    },
                )
                dao.touch(playlistId, t)
            }
            AddResult(toAdd.size, duplicates)
        }

    suspend fun removeEntries(entryIds: List<String>) {
        if (entryIds.isNotEmpty()) dao.deleteEntries(entryIds)
    }

    /** Gives the entry a key between its new neighbours; renumbers the playlist when keys get too close. */
    suspend fun moveEntry(playlistId: String, entryId: String, toIndex: Int) =
        write {
            val others = dao.entryRows(playlistId).filter { it.entryId != entryId }
            val index = toIndex.coerceIn(0, others.size)
            val before = others.getOrNull(index - 1)?.sortKey
            val after = others.getOrNull(index)?.sortKey
            val key = when {
                before != null && after != null -> (before + after) / 2
                before != null -> before + 1.0
                after != null -> after - 1.0
                else -> 1.0
            }
            val tooClose = (before != null && key - before < MIN_GAP) || (after != null && after - key < MIN_GAP)
            if (tooClose) {
                val ids = others.map { it.entryId }.toMutableList().apply { add(index, entryId) }
                ids.forEachIndexed { i, id -> dao.setSortKey(id, (i + 1).toDouble()) }
            } else {
                dao.setSortKey(entryId, key)
            }
            dao.touch(playlistId, now())
        }

    private suspend fun <T> write(block: suspend () -> T): T =
        db.useWriterConnection { it.immediateTransaction { block() } }

    private companion object {
        const val MIN_GAP = 1e-9
    }
}
