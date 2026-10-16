/**
 * Vega: after reinstalling or restoring a backup, recognises the files already in Music/Vega by the
 * [videoId] in their names, so they play offline without downloading them again. Songs the database
 * does not know yet are created from the file name; their details are completed when online.
 * Licensed under GPL-3.0
 */

package com.metrolist.music.offline

import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.DownloadEntity
import com.metrolist.music.models.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import javax.inject.Inject

class OfflineScanner @Inject constructor(
    private val database: MusicDatabase,
    private val store: OfflineStore,
) {
    data class ScanReport(val linked: Int, val newSongs: Int)

    companion object {
        /** Left by a backup restore: the next start scans Music/Vega once. */
        const val PENDING_SCAN_FILE = "offline_pending_scan"
    }

    suspend fun scan(): ScanReport = withContext(Dispatchers.IO) { link(store.scan()) }

    internal suspend fun link(files: List<OfflineStore.ScannedFile>): ScanReport {
        var newSongs = 0
        files.forEach { file ->
            if (database.getSongById(file.videoId) == null) {
                val parsed = OfflineFileName.parse(file.fileName)
                database.insert(
                    MediaMetadata(
                        id = file.videoId,
                        title = parsed?.title ?: file.fileName.substringBeforeLast('.'),
                        artists = listOfNotNull(parsed?.artist).map { MediaMetadata.Artist(id = null, name = it) },
                        duration = ((file.durationMs ?: 0L) / 1000).toInt(),
                    ),
                )
                newSongs++
            }
            database.upsertDownload(
                DownloadEntity(file.videoId, file.uri.toString(), file.fileName, file.size, file.mimeType, LocalDateTime.now()),
            )
        }
        return ScanReport(files.size, newSongs)
    }
}
