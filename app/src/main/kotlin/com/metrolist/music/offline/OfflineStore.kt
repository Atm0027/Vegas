/**
 * Vega: downloaded songs are real files. On Android 10+ they go to Music/Vega through MediaStore
 * (no permission needed to write, and they survive reinstalling Vega); on Android 8-9 they go to
 * the app's private Music/Vega folder. Reading files a previous install wrote needs the audio read
 * permission, so "no access" is kept apart from "missing": a row must not be dropped just because
 * this install cannot see the file yet.
 * Licensed under GPL-3.0
 */

package com.metrolist.music.offline

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OfflineStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    enum class Existence { PRESENT, MISSING, NO_ACCESS }

    sealed interface DeleteResult {
        data object Deleted : DeleteResult
        data class NeedsConfirmation(val intentSender: IntentSender) : DeleteResult
    }

    data class ScannedFile(
        val uri: Uri,
        val fileName: String,
        val videoId: String,
        val size: Long,
        val mimeType: String,
        val durationMs: Long?,
    )

    private val resolver get() = context.contentResolver
    private val usesMediaStore get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    private fun privateDir(): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: File(context.filesDir, "music"), FOLDER)
            .apply { mkdirs() }

    fun publish(temp: File, fileName: String, mimeType: String): Uri {
        if (!usesMediaStore) {
            val dest = File(privateDir(), fileName)
            dest.delete()
            if (!temp.renameTo(dest)) {
                temp.copyTo(dest, overwrite = true)
                temp.delete()
            }
            return Uri.fromFile(dest)
        }
        removeOwnFileNamed(fileName)
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri, "w")?.use { out -> temp.inputStream().use { it.copyTo(out) } }
                ?: throw IOException("Cannot open $uri")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        temp.delete()
        return uri
    }

    /** A file with the same name left by this install (e.g. an interrupted earlier publish). */
    private fun removeOwnFileNamed(fileName: String) {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        runCatching {
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                arrayOf(RELATIVE_PATH, fileName),
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    runCatching { resolver.delete(ContentUris.withAppendedId(collection, cursor.getLong(0)), null, null) }
                }
            }
        }
    }

    /**
     * [videoId] guards against a stored MediaStore id that now belongs to another file (backup
     * restored on another phone, media database rebuilt): such a row counts as missing.
     */
    fun exists(uri: Uri, videoId: String? = null): Existence =
        try {
            resolver.openFileDescriptor(uri, "r")?.close()
            if (videoId != null && uri.scheme == ContentResolver.SCHEME_CONTENT && displayName(uri)?.let(OfflineFileName::videoIdOf).let { it != null && it != videoId }) {
                Existence.MISSING
            } else {
                Existence.PRESENT
            }
        } catch (_: FileNotFoundException) {
            Existence.MISSING
        } catch (_: SecurityException) {
            Existence.NO_ACCESS
        } catch (_: IllegalArgumentException) {
            Existence.MISSING
        }

    private fun displayName(uri: Uri): String? =
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    fun delete(uri: Uri): DeleteResult {
        if (uri.scheme == "file") {
            uri.path?.let { File(it).delete() }
            return DeleteResult.Deleted
        }
        return try {
            resolver.delete(uri, null, null)
            DeleteResult.Deleted
        } catch (e: SecurityException) {
            deleteRequest(listOf(uri))?.let { DeleteResult.NeedsConfirmation(it) }
                ?: (e as? RecoverableSecurityException)?.let { DeleteResult.NeedsConfirmation(it.userAction.actionIntent.intentSender) }
                ?: throw e
        }
    }

    /** One system confirmation for several files this install did not create (Android 11+). */
    fun deleteRequest(uris: List<Uri>): IntentSender? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && uris.isNotEmpty()) {
            MediaStore.createDeleteRequest(resolver, uris).intentSender
        } else {
            null
        }

    fun scan(): List<ScannedFile> {
        if (!usesMediaStore) {
            return privateDir().listFiles().orEmpty().mapNotNull { file ->
                val id = OfflineFileName.videoIdOf(file.name) ?: return@mapNotNull null
                ScannedFile(Uri.fromFile(file), file.name, id, file.length(), mimeOf(file.name), null)
            }
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val result = mutableListOf<ScannedFile>()
        resolver.query(
            collection,
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.Audio.AudioColumns.DURATION,
            ),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(RELATIVE_PATH),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                val id = OfflineFileName.videoIdOf(name) ?: continue
                result += ScannedFile(
                    uri = ContentUris.withAppendedId(collection, c.getLong(0)),
                    fileName = name,
                    videoId = id,
                    size = c.getLong(2),
                    mimeType = c.getString(3) ?: mimeOf(name),
                    durationMs = if (c.isNull(4)) null else c.getLong(4),
                )
            }
        }
        return result
    }

    fun hasReadPermission(): Boolean =
        when {
            !usesMediaStore -> true
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> granted(Manifest.permission.READ_MEDIA_AUDIO)
            else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    /** The permission to ask for before [scan] can see files from a previous install. */
    fun readPermission(): String? =
        when {
            !usesMediaStore -> null
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> Manifest.permission.READ_MEDIA_AUDIO
            else -> Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun mimeOf(name: String) = if (name.endsWith(".webm")) "audio/webm" else "audio/mp4"

    companion object {
        const val FOLDER = "Vega"
        const val RELATIVE_PATH = "Music/Vega/"
    }
}
