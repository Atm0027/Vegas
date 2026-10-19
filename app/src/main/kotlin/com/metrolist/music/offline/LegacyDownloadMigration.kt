/**
 * Vegas: one-time move of downloads made by the old system (opaque chunks in the private ExoPlayer
 * download cache) into real files in Music/Vegas, without using the network. Downloads the old
 * cache holds only partly are queued again through the new system. The cache space is freed after.
 * Licensed under GPL-3.0
 */

package com.metrolist.music.offline

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import com.metrolist.music.constants.OfflineMigrationDoneKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.di.DownloadCache
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.safeDataStoreEdit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject

@androidx.annotation.OptIn(UnstableApi::class)
class LegacyDownloadMigration @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
    private val databaseProvider: DatabaseProvider,
    @DownloadCache private val downloadCache: Cache,
    private val downloader: SongDownloader,
    private val queue: DownloadQueue,
) {
    data class MigrationReport(val copied: Int, val requeued: Int)

    enum class Copy { COPIED, INCOMPLETE, FAILED }

    data class Cleanup(val remove: Set<String>, val done: Boolean)

    suspend fun isDone(): Boolean = context.dataStore.data.first()[OfflineMigrationDoneKey] == true

    suspend fun run(): MigrationReport = withContext(Dispatchers.IO) {
        val index = DefaultDownloadIndex(databaseProvider)
        val legacy = buildList {
            index.getDownloads().use { cursor -> while (cursor.moveToNext()) add(cursor.download) }
        }
        val results = legacy.associate { download ->
            val songId = download.request.id
            songId to when {
                download.state != Download.STATE_COMPLETED -> Copy.INCOMPLETE
                else -> runCatching { if (copyOut(songId)) Copy.COPIED else Copy.INCOMPLETE }
                    .onFailure { Timber.tag(TAG).w(it, "Could not migrate $songId") }
                    .getOrDefault(Copy.FAILED)
            }
        }
        val copied = results.count { it.value == Copy.COPIED }
        val requeue = results.filterValues { it != Copy.COPIED }.keys.toList()
        if (requeue.isNotEmpty()) queue.enqueueAutomatic(requeue, allowMobileData = false)
        val plan = cleanup(results)
        plan.remove.forEach { songId ->
            runCatching { downloadCache.removeResource(songId) }
            runCatching { index.removeDownload(songId) }
        }
        if (plan.done) context.safeDataStoreEdit { it[OfflineMigrationDoneKey] = true }
        MigrationReport(copied, requeue.size)
    }

    private suspend fun copyOut(songId: String): Boolean {
        if (database.downloadEntity(songId) != null) return true
        val length = ContentMetadata.getContentLength(downloadCache.getContentMetadata(songId))
        if (length <= 0 || !downloadCache.isCached(songId, 0, length)) return false

        val part = File(context.cacheDir, "offline/$songId.legacy").apply { parentFile?.mkdirs() }
        val source = CacheDataSource.Factory().setCache(downloadCache).setCacheWriteDataSinkFactory(null).createDataSource()
        try {
            source.open(DataSpec.Builder().setUri(Uri.parse(songId)).setKey(songId).build())
            part.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = source.read(buffer, 0, buffer.size)
                    if (read == C.RESULT_END_OF_INPUT) break
                    out.write(buffer, 0, read)
                }
            }
        } finally {
            source.close()
        }
        if (!verifySize(part, length)) return false
        val head = part.inputStream().use { input -> ByteArray(8).also { input.read(it) } }
        downloader.publishFile(songId, part, sniffMime(head))
        return true
    }

    companion object {
        private const val TAG = "LegacyMigration"

        /** Cached audio is dropped only once it is copied or was never complete; failures retry next start. */
        fun cleanup(results: Map<String, Copy>): Cleanup =
            Cleanup(
                remove = results.filterValues { it != Copy.FAILED }.keys,
                done = results.values.none { it == Copy.FAILED },
            )
    }
}

/** Container of a legacy download: MP4 ("ftyp" at bytes 4..7) or WebM (EBML magic); MP4 if unsure. */
internal fun sniffMime(head: ByteArray): String =
    when {
        head.size >= 8 && String(head, 4, 4, Charsets.US_ASCII) == "ftyp" -> "audio/mp4"
        head.size >= 4 && head[0] == 0x1A.toByte() && head[1] == 0x45.toByte() &&
            head[2] == 0xDF.toByte() && head[3] == 0xA3.toByte() -> "audio/webm"
        else -> "audio/mp4"
    }
