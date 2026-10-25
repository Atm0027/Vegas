/**
 * Vegas desktop: only one Vegas runs at a time. A second launch asks the running one to show its window
 * (through the control API) and exits; a second silent launch just exits, since Vegas is already ready.
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.desktop.system

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration

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

    /** Asks the running Vegas to bring its window to the front. */
    fun showRunning(port: Int, token: String?) {
        if (token == null) return
        runCatching {
            val client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).connectTimeout(Duration.ofSeconds(2)).build()
            val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v1/mostrar"))
                .header("Authorization", "Bearer $token")
                .timeout(Duration.ofSeconds(3))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build()
            client.send(request, HttpResponse.BodyHandlers.discarding())
        }
    }
}

/** Command-line options. */
data class LaunchOptions(val silent: Boolean) {
    companion object {
        const val SILENT = "--silencioso"

        fun parse(args: Array<String>) = LaunchOptions(silent = args.any { it.equals(SILENT, ignoreCase = true) })
    }
}
