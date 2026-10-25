/**
 * Vegas desktop: where downloads go (Música\Vegas by default, wherever Windows keeps «Música») and
 * how a download is removed (to the Recycle Bin when Windows allows it, so nothing is lost for good).
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.desktop.offline

import com.sun.jna.platform.win32.KnownFolders
import com.sun.jna.platform.win32.Shell32Util
import java.awt.Desktop
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.file.Files
import java.nio.file.Path

object OfflineFolder {
    fun defaultDir(): Path {
        val music = runCatching { Path.of(Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_Music)) }
            .getOrElse { Path.of(System.getProperty("user.home"), "Music") }
        return music.resolve("Vegas")
    }

    fun moveToTrash(path: Path) {
        val trashed = runCatching {
            Desktop.isDesktopSupported() &&
                Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH) &&
                Desktop.getDesktop().moveToTrash(path.toFile())
        }.getOrDefault(false)
        if (!trashed) Files.deleteIfExists(path)
    }

    fun mimeTypeOf(fileName: String): String =
        when (fileName.substringAfterLast('.').lowercase()) {
            "m4a", "mp4" -> "audio/mp4"
            "webm" -> "audio/webm"
            "opus", "ogg" -> "audio/ogg"
            else -> "audio/mpeg"
        }

    fun extFor(mimeType: String): String = if (mimeType == "audio/webm") "webm" else "m4a"
}

/** Recognises a lost or unusable connection however deep it is wrapped. */
object NetworkFailure {
    fun isNetworkFailure(error: Throwable): Boolean =
        generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }
            .take(16)
            .any {
                it is ConnectException || it is UnknownHostException || it is SocketTimeoutException ||
                    it is NoRouteToHostException || it.javaClass.simpleName.contains("Timeout")
            }
}
