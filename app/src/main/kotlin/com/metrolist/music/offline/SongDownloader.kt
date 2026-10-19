/**
 * Vegas: downloads one song to a real, tagged audio file. Always asks YouTube for the m4a stream
 * whatever the playback quality setting, saves it to a private .part file (resumable), checks the
 * size, writes title/artists/album/cover and only then publishes it to Music/Vegas, so an
 * interrupted download never leaves a broken file behind.
 * Licensed under GPL-3.0
 */

package com.metrolist.music.offline

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import androidx.core.content.getSystemService
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Size
import coil3.toBitmap
import com.metrolist.innertube.YouTube
import com.metrolist.innertubex.extraction.AudioQuality as InnerTubeXAudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.music.constants.AudioQuality
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.DownloadEntity
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.music.playback.downloadContentLength
import com.metrolist.music.utils.InnerTubeXPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

sealed interface DownloadOutcome {
    data class Done(val entity: DownloadEntity) : DownloadOutcome
    data class Retry(val cause: Throwable) : DownloadOutcome
    data class Failed(val cause: Throwable) : DownloadOutcome
}

@Singleton
class SongDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
    private val store: OfflineStore,
) {
    private val connectivityManager by lazy { context.getSystemService<ConnectivityManager>()!! }
    private val http by lazy {
        OkHttpClient.Builder()
            .proxy(YouTube.proxy)
            .proxyAuthenticator { _, response ->
                YouTube.proxyAuth?.let { response.request.newBuilder().header("Proxy-Authorization", it).build() }
                    ?: response.request
            }
            .build()
    }

    suspend fun download(songId: String, onProgress: (Float) -> Unit): DownloadOutcome =
        withContext(Dispatchers.IO) {
            try {
                database.downloadEntity(songId)?.let { existing ->
                    if (store.exists(android.net.Uri.parse(existing.uri), songId) != OfflineStore.Existence.MISSING) {
                        return@withContext DownloadOutcome.Done(existing)
                    }
                }
                downloadNow(songId, onProgress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Timber.tag(TAG).w(e, "Download of $songId will be retried")
                DownloadOutcome.Retry(e)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Download of $songId failed")
                DownloadOutcome.Failed(e)
            }
        }

    private suspend fun downloadNow(songId: String, onProgress: (Float) -> Unit): DownloadOutcome {
        val song = database.getSongById(songId)
        val playback = InnerTubeXPlayer.playerResponseForPlayback(
            songId,
            audioQuality = AudioQuality.AUTO,
            connectivityManager = connectivityManager,
            contentHints = ContentHints(isExplicit = song?.song?.explicit, isUploaded = song?.song?.isUploaded),
            allowBoundedRange = false,
            qualityOverride = InnerTubeXAudioQuality.MP4,
        ).getOrThrow()

        val mimeType = playback.format.mimeType.substringBefore(";").trim()
        val length = playback.format.contentLength?.takeIf { it > 0 }
            ?: probeLength(playback.streamUrl, playback.streamHeaders)
            ?: throw IOException("Unknown size for $songId")

        if (song == null) {
            database.insert(
                SongEntity(
                    id = songId,
                    title = playback.videoDetails?.title ?: songId,
                    duration = playback.videoDetails?.lengthSeconds?.toIntOrNull() ?: 0,
                    thumbnailUrl = playback.videoDetails?.thumbnail?.thumbnails?.lastOrNull()?.url,
                ),
            )
        }

        val dir = File(context.cacheDir, "offline").apply { mkdirs() }
        val partName = partName(songId, mimeType, length)
        dir.listFiles { file -> file.name.startsWith("$songId-") && file.name != partName }?.forEach { it.delete() }
        val part = File(dir, partName)
        fetch(playback.streamUrl, playback.streamHeaders, length, part, onProgress)
        if (!verifySize(part, length)) throw IOException("Incomplete download of $songId")

        val entity = publishFile(
            songId = songId,
            part = part,
            mimeType = mimeType,
            fallbackTitle = playback.videoDetails?.title,
            fallbackArtist = playback.videoDetails?.author,
            fallbackCover = playback.videoDetails?.thumbnail?.thumbnails?.lastOrNull()?.url,
        )
        onProgress(1f)
        return DownloadOutcome.Done(entity)
    }

    /** Tags a complete audio file, publishes it to Music/Vegas and records it as downloaded. */
    suspend fun publishFile(
        songId: String,
        part: File,
        mimeType: String,
        fallbackTitle: String? = null,
        fallbackArtist: String? = null,
        fallbackCover: String? = null,
    ): DownloadEntity {
        val song = database.getSongById(songId)
        val title = song?.song?.title ?: fallbackTitle ?: songId
        val artists = song?.orderedArtists?.map { it.name }.orEmpty().ifEmpty { listOfNotNull(fallbackArtist) }
        if (mimeType == "audio/mp4") {
            runCatching {
                val cover = runCatching { cover(song?.song?.thumbnailUrl ?: song?.album?.thumbnailUrl ?: fallbackCover) }.getOrNull()
                M4aTagger.write(part, SongTags(title, artists, song?.album?.title, cover))
            }.onFailure { Timber.tag(TAG).w(it, "Could not tag $songId") }
        }

        val fileName = OfflineFileName.build(SongTags(title, artists, null, null).artistLine(), title, songId, extFor(mimeType))
        val size = part.length()
        val uri = store.publish(part, fileName, mimeType)
        val entity = DownloadEntity(songId, uri.toString(), fileName, size, mimeType, LocalDateTime.now())
        database.upsertDownload(entity)
        return entity
    }

    private suspend fun fetch(url: String, headers: Map<String, String>, length: Long, part: File, onProgress: (Float) -> Unit) {
        var existing = if (part.exists()) part.length() else 0L
        if (existing >= length) {
            part.delete()
            existing = 0L
        }
        val request = Request.Builder()
            .get()
            .url(rangedUrl(url, length, from = existing))
            .apply {
                headers.forEach { (name, value) -> header(name, value) }
                if (existing > 0) header("Range", resumeHeader(existing))
            }
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val mode = resumeMode(
                code = response.code,
                existing = existing,
                contentRange = response.header("Content-Range"),
                contentLength = response.header("Content-Length")?.toLongOrNull(),
                length = length,
            )
            if (mode == Resume.MISMATCH) {
                part.delete()
                throw IOException("Resume of ${part.name} did not continue at byte $existing")
            }
            val append = mode == Resume.APPEND
            var written = if (append) existing else 0L
            val body = response.body
            FileOutputStream(part, append).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var lastReport = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        written += read
                        if (written - lastReport > 256 * 1024) {
                            lastReport = written
                            onProgress((written.toFloat() / length).coerceAtMost(0.99f))
                        }
                    }
                }
            }
        }
    }

    private fun probeLength(url: String, headers: Map<String, String>): Long? {
        val request = Request.Builder().get().url(url)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .header("Range", "bytes=0-0")
            .build()
        return http.newCall(request).execute().use { response ->
            downloadContentLength(response.code, response.header("Content-Range"), response.header("Content-Length"))
        }
    }

    private suspend fun cover(url: String?): ByteArray? {
        if (url.isNullOrBlank()) return null
        val result = context.imageLoader.execute(
            ImageRequest.Builder(context).data(url).size(Size(COVER_SIZE, COVER_SIZE)).build(),
        )
        val bitmap = (result as? SuccessResult)?.image?.toBitmap() ?: return null
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.toByteArray()
        }
    }

    private companion object {
        const val TAG = "SongDownloader"
        const val COVER_SIZE = 544
    }
}

/** googlevideo throttles open-ended requests to playback speed; an explicit range downloads at full speed. */
internal fun rangedUrl(url: String, length: Long, from: Long = 0L): String =
    if ("&range=" in url || "?range=" in url) url else "$url&range=$from-${length - 1}"

internal fun resumeHeader(existing: Long): String = "bytes=$existing-"

/** A partial file only resumes into the same format and length; another stream starts its own. */
internal fun partName(songId: String, mimeType: String, length: Long): String =
    "$songId-${mimeType.replace('/', '_')}-$length.part"

internal enum class Resume { APPEND, RESTART, MISMATCH }

/**
 * Appends only when the response provably starts where the part ends: a 206 whose Content-Range
 * begins at [existing], or a 200 (googlevideo honours the range parameter) carrying exactly the
 * missing bytes. A full body restarts the file; anything else would splice unrelated bytes.
 */
internal fun resumeMode(code: Int, existing: Long, contentRange: String?, contentLength: Long?, length: Long): Resume =
    when {
        existing <= 0 -> Resume.RESTART
        code == 206 -> if (contentRange?.startsWith("bytes $existing-") == true) Resume.APPEND else Resume.MISMATCH
        contentLength == length - existing -> Resume.APPEND
        contentLength == length || contentLength == null -> Resume.RESTART
        else -> Resume.MISMATCH
    }

/** Keeps [part] only when it has exactly [expected] bytes; otherwise deletes it. */
internal fun verifySize(part: File, expected: Long): Boolean {
    if (part.length() == expected) return true
    part.delete()
    return false
}

internal fun extFor(mimeType: String): String = if (mimeType == "audio/webm") "webm" else "m4a"
