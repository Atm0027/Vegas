/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 * Vega: facade over the offline download system (real files in Music/Vega, WorkManager queue).
 * It keeps the old `downloads` map of Media3 [Download]s so the existing screens keep working.
 */

package com.metrolist.music.playback

import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.widget.Toast
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadProgress
import androidx.media3.exoplayer.offline.DownloadRequest
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.R
import androidx.datastore.preferences.core.edit
import com.metrolist.music.constants.DownloadLikedKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.AlbumEntity
import com.metrolist.music.db.entities.DownloadEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.offline.DownloadQueue
import com.metrolist.music.offline.OfflineScanner
import com.metrolist.music.offline.OfflineStore
import com.metrolist.music.offline.QueueState
import com.metrolist.music.utils.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadUtil
@Inject
constructor(
    @ApplicationContext private val context: Context,
    val database: MusicDatabase,
    private val queue: DownloadQueue,
    private val store: OfflineStore,
    private val scanner: OfflineScanner,
) {
    /** Files Android only lets the user delete after a system confirmation (another install made them). */
    data class PendingDelete(val songIds: List<String>, val intentSender: IntentSender)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val downloads: StateFlow<Map<String, Download>> =
        combine(database.allDownloads(), queue.states(), ::combineDownloads)
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private val _deleteRequests = MutableSharedFlow<PendingDelete>(extraBufferCapacity = 4)
    val deleteRequests: SharedFlow<PendingDelete> = _deleteRequests

    fun getDownload(songId: String): Flow<Download?> = downloads.map { it[songId] }

    fun download(song: Song) = download(song.toMediaMetadata())

    fun download(song: SongItem) = download(song.toMediaMetadata())

    fun download(mediaMetadata: MediaMetadata) {
        scope.launch {
            if (downloads.value[mediaMetadata.id]?.state == Download.STATE_COMPLETED) return@launch
            mediaMetadata.album?.let { album ->
                if (database.albumEntity(album.id) == null) {
                    database.insert(
                        AlbumEntity(
                            id = album.id,
                            title = album.title,
                            thumbnailUrl = mediaMetadata.thumbnailUrl,
                            songCount = 0,
                            duration = 0,
                        ),
                    )
                }
            }
            val existing = database.getSongByIdBlocking(mediaMetadata.id)
            if (existing == null) {
                database.insert(mediaMetadata)
            } else {
                database.update(existing, mediaMetadata, overwriteTitle = false, overwriteArtists = false)
            }
            queue.enqueueManual(mediaMetadata.id)
        }
    }

    fun download(songId: String) {
        scope.launch {
            if (downloads.value[songId]?.state == Download.STATE_COMPLETED) return@launch
            queue.enqueueManual(songId)
        }
    }

    /** Marks the playlist as downloaded (it stays downloaded as songs are added) and queues its songs now. */
    fun downloadPlaylist(playlistId: String, songs: List<Song>) {
        scope.launch { database.setPlaylistDownloaded(playlistId, true) }
        songs.forEach { download(it) }
    }

    /** Unmarks the playlist; files are only deleted when asked, and never those another marked list keeps. */
    fun removePlaylistDownload(playlistId: String, songIds: List<String>, deleteFiles: Boolean) {
        scope.launch {
            database.setPlaylistDownloaded(playlistId, false)
            val includeLiked = context.dataStore.data.first()[DownloadLikedKey] ?: false
            val stillWanted = database.songsToAutoDownload(includeLiked).first().toSet()
            songIds.filter { it !in stillWanted && downloads.value[it]?.state != Download.STATE_COMPLETED }
                .forEach(queue::cancel)
            if (deleteFiles) removeAll(database.songsOnlyInPlaylist(playlistId, includeLiked))
        }
    }

    /** Liked stops being a downloaded playlist; files go only when asked and no marked playlist keeps them. */
    fun removeLikedDownload(songIds: List<String>, deleteFiles: Boolean) {
        scope.launch {
            val stillWanted = database.songsToAutoDownload(false).first().toSet()
            songIds.filter { it !in stillWanted && downloads.value[it]?.state != Download.STATE_COMPLETED }
                .forEach(queue::cancel)
            if (deleteFiles) {
                removeAll(songIds.filter { database.downloadEntity(it) != null && database.trackedPlaylistNames(it).isEmpty() })
            }
        }
    }

    fun readPermission(): String? = store.readPermission()

    fun hasReadPermission(): Boolean = store.hasReadPermission()

    /** Recognises files already in Music/Vega (previous install or restored backup). */
    fun scanFolder(onDone: (OfflineScanner.ScanReport) -> Unit = {}) {
        scope.launch {
            val report = runCatching { scanner.scan() }
                .onFailure { Timber.tag(TAG).e(it, "Music/Vega scan failed") }
                .getOrDefault(OfflineScanner.ScanReport(0, 0))
            withContext(Dispatchers.Main) { onDone(report) }
        }
    }

    fun remove(songId: String) = removeAll(listOf(songId))

    /** Cancels queued work and deletes the files; asks once for files another install created. */
    /** Deletes every download and unmarks the tracked lists, so nothing is downloaded again. */
    fun removeEverything(songIds: List<String>) {
        scope.launch {
            database.untrackAllPlaylists()
            context.dataStore.edit { it[DownloadLikedKey] = false }
            removeAll(songIds)
        }
    }

    fun removeAll(songIds: List<String>) {
        if (songIds.isEmpty()) return
        scope.launch {
            val needsConfirmation = mutableListOf<Pair<String, Uri>>()
            val singleRequests = mutableListOf<PendingDelete>()
            songIds.forEach { songId ->
                queue.cancel(songId)
                val entity = database.downloadEntity(songId) ?: return@forEach
                val uri = entity.uri.toUri()
                runCatching { store.delete(uri) }
                    .onSuccess { result ->
                        when (result) {
                            OfflineStore.DeleteResult.Deleted -> database.deleteDownload(songId)
                            is OfflineStore.DeleteResult.NeedsConfirmation -> {
                                needsConfirmation += songId to uri
                                singleRequests += PendingDelete(listOf(songId), result.intentSender)
                            }
                        }
                    }.onFailure { Timber.tag(TAG).e(it, "Could not delete download $songId") }
            }
            if (needsConfirmation.isNotEmpty()) {
                val grouped = store.deleteRequest(needsConfirmation.map { it.second })
                if (grouped != null) {
                    _deleteRequests.emit(PendingDelete(needsConfirmation.map { it.first }, grouped))
                } else {
                    singleRequests.forEach { _deleteRequests.emit(it) }
                }
            }
            warnIfTracked(songIds)
        }
    }

    /** Called once the system confirmed deleting files from another install. */
    fun confirmDeleted(songIds: List<String>) {
        scope.launch { songIds.forEach { database.deleteDownload(it) } }
    }

    private suspend fun warnIfTracked(songIds: List<String>) {
        if (songIds.size != 1) return
        val playlist = database.trackedPlaylistNames(songIds.first()).firstOrNull() ?: return
        withContext(Dispatchers.Main) {
            Toast.makeText(context, context.getString(R.string.download_will_return, playlist), Toast.LENGTH_LONG).show()
        }
    }

    fun release() {
        scope.cancel()
    }

    private companion object {
        const val TAG = "DownloadUtil"
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun toExoDownload(songId: String, state: Int, progress: Float, size: Long): Download {
    val request = DownloadRequest.Builder(songId, songId.toUri()).setCustomCacheKey(songId).build()
    val downloadProgress = DownloadProgress().apply {
        percentDownloaded = progress * 100f
        bytesDownloaded = (size * progress).toLong()
    }
    val now = System.currentTimeMillis()
    return Download(
        request,
        state,
        now,
        now,
        if (size > 0) size else C.LENGTH_UNSET.toLong(),
        Download.STOP_REASON_NONE,
        Download.FAILURE_REASON_NONE,
        downloadProgress,
    )
}

/** Finished files win over a queue entry for the same song (its work may not be marked done yet). */
internal fun combineDownloads(files: List<DownloadEntity>, queue: Map<String, QueueState>): Map<String, Download> =
    buildMap {
        queue.forEach { (songId, state) ->
            val exoState = if (state.running) Download.STATE_DOWNLOADING else Download.STATE_QUEUED
            put(songId, toExoDownload(songId, exoState, state.progress, 0))
        }
        files.forEach { put(it.songId, toExoDownload(it.songId, Download.STATE_COMPLETED, 1f, it.size)) }
    }

internal fun shouldPrepareDownload(downloadState: Int?): Boolean = downloadState != Download.STATE_COMPLETED

internal fun downloadArtworkUrls(
    songArtwork: String?,
    albumArtwork: String?,
): List<String> = listOfNotNull(songArtwork, albumArtwork).filter(String::isNotBlank).distinct()

internal fun downloadContentLength(
    statusCode: Int,
    contentRange: String?,
    contentLength: String?,
): Long? {
    val rangePattern =
        when (statusCode) {
            206 -> PARTIAL_CONTENT_RANGE
            416 -> UNSATISFIED_CONTENT_RANGE
            else -> null
        }
    if (rangePattern != null) {
        return contentRange
            ?.trim()
            ?.let(rangePattern::matchEntire)
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
    }
    return if (statusCode == 200) contentLength?.toLongOrNull()?.takeIf { it > 0L } else null
}

private val PARTIAL_CONTENT_RANGE = Regex("""bytes\s+0-0/(\d+)""", RegexOption.IGNORE_CASE)
private val UNSATISFIED_CONTENT_RANGE = Regex("""bytes\s+\*/(\d+)""", RegexOption.IGNORE_CASE)
