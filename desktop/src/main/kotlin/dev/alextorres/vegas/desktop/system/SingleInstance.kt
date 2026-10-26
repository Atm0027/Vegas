/**
 * Vegas desktop: only one Vegas runs at a time. A second launch asks the running one to show its window
 * (through the control API) and exits; a second silent launch just exits, since Vegas is already ready.
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.desktop.system

import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

object SingleInstance {
    /** Kept for the whole run: the lock lasts as long as the channel is open. */
    private var lock: FileLock? = null

    /** True if this is the only Vegas running. */
    fun acquire(lockFile: Path): Boolean {
        Files.createDirectories(lockFile.toAbsolutePath().parent)
        val channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        val acquired = runCatching { channel.tryLock() }.getOrNull()
        if (acquired == null) {
            channel.close()
            return false
        }
        lock = acquired
        return true
    }

    /**
     * Asks the running Vegas to bring its window to the front. Uses HttpURLConnection (java.base): the
     * bundled runtime has no java.net.http. Returns the failure, if any, for the log.
     */
    fun showRunning(port: Int, token: String?): Throwable? {
        if (token == null) return IllegalStateException("Sin control.token")
        return runCatching {
            val connection = URI("http://127.0.0.1:$port/v1/mostrar").toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 2_000
                connection.readTimeout = 3_000
                connection.setRequestProperty("Authorization", "Bearer $token")
                connection.doOutput = true
                connection.outputStream.close()
                val code = connection.responseCode
                check(code == 200) { "Respuesta $code" }
            } finally {
                connection.disconnect()
            }
        }.exceptionOrNull()
    }
}

/** Command-line options. */
data class LaunchOptions(val silent: Boolean) {
    companion object {
        const val SILENT = "--silencioso"

        fun parse(args: Array<String>) = LaunchOptions(silent = args.any { it.equals(SILENT, ignoreCase = true) })
    }
}
