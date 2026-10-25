/**
 * Vegas desktop: downloads one song to a real, tagged audio file, like the phone's SongDownloader.
 * Always the m4a stream whatever the playback quality, into a resumable .part file under
 * %APPDATA%\Vegas; the size is checked and the tags written before the file is moved into the
 * download folder, so an interrupted download never leaves a broken file there.
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.desktop.offline

import dev.alextorres.vegas.desktop.db.DownloadEntity
import dev.alextorres.vegas.desktop.db.SongEntity
import dev.alextorres.vegas.desktop.ui.thumbnail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** An audio stream to download; [contentLength] is null when YouTube does not say. */
data class DownloadStream(
    val url: String,
    val headers: Map<String, String>,
    val mimeType: String,
    val contentLength: Long?,
)

class SongDownloader(
    private val resolve: suspend (videoId: String) -> DownloadStream,
    private val offline: OfflineLibrary,
    private val tempDir: Path,
    private val http: OkHttpClient = OkHttpClient(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun download(song: SongEntity, onProgress: (Float) -> Unit) {
        val stream = resolve(song.id)
        val mimeType = stream.mimeType.substringBefore(";").trim().ifEmpty { "audio/mp4" }
        withContext(Dispatchers.IO) {
            val length = stream.contentLength?.takeIf { it > 0 }
                ?: probeLength(stream.url, stream.headers)
                ?: throw IOException("Unknown size for ${song.id}")
            val dir = tempDir.toFile().apply { mkdirs() }
            val partName = partName(song.id, mimeType, length)
            dir.listFiles { f -> f.name.startsWith("${song.id}-") && f.name != partName }?.forEach { it.delete() }
            val part = File(dir, partName)
            fetch(stream.url, stream.headers, length, part, onProgress)
            if (!verifySize(part, length)) throw IOException("Incomplete download of ${song.id}")

            val artists = song.artists.split(", ").filter { it.isNotBlank() }
            if (mimeType == "audio/mp4") {
                runCatching {
                    M4aTagger.write(part, SongTags(song.title, artists, song.album, cover(song.thumbnailUrl)))
                }
            }
            val folder = offline.folder().also { Files.createDirectories(it) }
            val target = folder.resolve(OfflineFileName.build(song.artists, song.title, song.id, OfflineFolder.extFor(mimeType)))
            val size = part.length()
            moveInto(part.toPath(), target)
            offline.record(DownloadEntity(song.id, target.toAbsolutePath().toString(), size, mimeType, now()))
        }
        onProgress(1f)
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
            FileOutputStream(part, append).use { out ->
                response.body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var lastReport = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
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
            response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
        }
    }

    private fun cover(url: String?): ByteArray? {
        val sized = thumbnail(url, COVER_SIZE) ?: return null
        return runCatching {
            http.newCall(Request.Builder().get().url(sized).build()).execute().use { r ->
                if (r.isSuccessful) r.body.bytes() else null
            }
        }.getOrNull()
    }

    private companion object {
        const val COVER_SIZE = 544
    }
}

/** Moves a finished file into the folder, replacing an older copy of the same song. */
internal fun moveInto(source: Path, target: Path) {
    try {
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: IOException) {
        // Another drive: copy to a temporary name first so the folder never holds a half file.
        val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
        Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING)
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
        Files.delete(source)
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
