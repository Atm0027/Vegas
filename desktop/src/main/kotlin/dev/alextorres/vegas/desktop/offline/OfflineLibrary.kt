/**
 * Vegas desktop: what is downloaded and what should be, with the phone's rules — a marked list keeps
 * itself downloaded, removing a song from a list never deletes its file, and unmarking a list only
 * deletes the files no other marked list still needs.
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.desktop.offline

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import dev.alextorres.vegas.desktop.db.DownloadEntity
import dev.alextorres.vegas.desktop.db.DownloadRequestEntity
import dev.alextorres.vegas.desktop.db.OfflinePlaylistEntity
import dev.alextorres.vegas.desktop.db.SongEntity
import dev.alextorres.vegas.desktop.db.VegasDatabase
import dev.alextorres.vegas.desktop.db.toEntity
import dev.alextorres.vegas.desktop.playback.OfflineSource
import dev.alextorres.vegas.desktop.playback.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

class OfflineLibrary(
    private val db: VegasDatabase,
    scope: CoroutineScope,
    /** Where new downloads go and which folder [scan] looks at. */
    val folder: () -> Path,
    private val remove: (Path) -> Unit = OfflineFolder::moveToTrash,
    private val now: () -> Long = System::currentTimeMillis,
) : OfflineSource {
    private val dao = db.offline()

    val downloads: StateFlow<List<DownloadEntity>> = dao.downloads().stateIn(scope, SharingStarted.Eagerly, emptyList())

    val downloadedIds: StateFlow<Set<String>> =
        dao.downloads().map { rows -> rows.mapTo(HashSet()) { it.songId } }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    val offlinePlaylists: StateFlow<Set<String>> =
        dao.offlinePlaylistIds().map { it.toSet() }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    fun pending(): Flow<List<SongEntity>> = dao.pending()

    override fun isDownloaded(songId: String): Boolean = songId in downloadedIds.value

    /** The song's file, or null; a row whose file has gone is dropped (a marked list then downloads it again). */
    override suspend fun localFile(songId: String): Path? {
        val row = dao.download(songId) ?: return null
        val path = Path.of(row.path)
        if (withContext(Dispatchers.IO) { path.isRegularFile() }) return path
        dao.deleteDownloads(listOf(songId))
        return null
    }

    suspend fun markPlaylist(playlistId: String) = dao.markPlaylist(OfflinePlaylistEntity(playlistId, now()))

    suspend fun unmarkPlaylist(playlistId: String, deleteFiles: Boolean) {
        val unneeded = write {
            val others = dao.songIdsInOtherOfflinePlaylists(playlistId).toSet()
            dao.unmarkPlaylist(playlistId)
            dao.songIdsIn(playlistId).filter { it !in others }
        }
        if (deleteFiles) deleteFiles(unneeded)
    }

    suspend fun request(tracks: List<Track>) =
        write {
            tracks.forEach { track ->
                dao.insertSongIfMissing(track.toEntity())
                dao.request(DownloadRequestEntity(track.id, now()))
            }
        }

    /** Deletes the song's file; returns the marked list that will download it again, if any. */
    suspend fun deleteDownload(songId: String): String? {
        deleteFiles(listOf(songId))
        return dao.offlinePlaylistHolding(songId)
    }

    /** Called by the downloader once a file is complete and in place. */
    suspend fun record(download: DownloadEntity) =
        write {
            dao.upsertDownload(download)
            dao.deleteRequests(listOf(download.songId))
        }

    /**
     * Brings the table in line with the disk: rows whose file is gone are dropped, and files in the
     * folder named «… [videoId].ext» are adopted (creating the song from its name if the library does
     * not know it). Returns how many files were adopted.
     */
    suspend fun scan(): Int {
        val rows = dao.downloadRows()
        val (present, gone) = withContext(Dispatchers.IO) { rows.partition { Path.of(it.path).isRegularFile() } }
        if (gone.isNotEmpty()) dao.deleteDownloads(gone.map { it.songId })
        val known = present.mapTo(HashSet()) { it.songId }
        val dir = folder()
        val files = withContext(Dispatchers.IO) {
            if (!Files.isDirectory(dir)) emptyList() else dir.listDirectoryEntries().filter { it.isRegularFile() }
        }
        var adopted = 0
        for (file in files) {
            val parsed = OfflineFileName.parse(file.name) ?: continue
            if (!known.add(parsed.videoId)) continue
            val size = withContext(Dispatchers.IO) { file.fileSize() }
            write {
                dao.insertSongIfMissing(SongEntity(parsed.videoId, parsed.title, parsed.artist.orEmpty(), null, null, null))
                dao.upsertDownload(DownloadEntity(parsed.videoId, file.toAbsolutePath().toString(), size, OfflineFolder.mimeTypeOf(file.name), now()))
                dao.deleteRequests(listOf(parsed.videoId))
            }
            adopted++
        }
        return adopted
    }

    private suspend fun deleteFiles(songIds: List<String>) {
        if (songIds.isEmpty()) return
        val wanted = songIds.toSet()
        val rows = dao.downloadRows().filter { it.songId in wanted }
        withContext(Dispatchers.IO) { rows.forEach { runCatching { remove(Path.of(it.path)) } } }
        dao.deleteDownloads(songIds)
        dao.deleteRequests(songIds)
    }

    private suspend fun <T> write(block: suspend () -> T): T =
        db.useWriterConnection { it.immediateTransaction { block() } }
}
