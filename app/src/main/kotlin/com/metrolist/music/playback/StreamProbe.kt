/**
 * Vegas: a stream address YouTube refuses (HTTP 403/410) used to reach the player, which showed the
 * error, waited and resolved again. Asking for the first byte right after resolving catches it while
 * the song is still loading, so the next client is tried at once and the error never shows.
 * Licensed under GPL-3.0
 */

package com.metrolist.music.playback

import com.metrolist.innertube.YouTube
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object StreamProbe {
    enum class Verdict { PLAYABLE, REJECTED, UNKNOWN }

    /** Rejected streams retried with another client before handing the last one to the player. */
    const val MAX_REJECTIONS = 2

    private const val TIMEOUT_MS = 3_000L

    fun verdict(httpCode: Int): Verdict =
        when (httpCode) {
            403, 410 -> Verdict.REJECTED
            in 200..299 -> Verdict.PLAYABLE
            else -> Verdict.UNKNOWN
        }

    /**
     * Resolves with [resolve] and checks the result with [probe]. A rejected stream is reported to
     * [onRejected] (so its client is excluded) and resolved again, up to [maxRejections] times.
     * Anything the probe cannot tell (slow network, other codes) plays as before. If no other client
     * works, the last rejected stream is returned and the player's own recovery takes over.
     */
    suspend fun <T> resolveVerified(
        maxRejections: Int = MAX_REJECTIONS,
        resolve: suspend () -> Result<T>,
        probe: suspend (T) -> Verdict,
        onRejected: (T) -> Unit,
    ): Result<T> {
        var rejected: T? = null
        repeat(maxRejections + 1) { attempt ->
            val result = resolve()
            val value = result.getOrElse { return rejected?.let { Result.success(it) } ?: result }
            if (attempt == maxRejections || probe(value) != Verdict.REJECTED) return Result.success(value)
            onRejected(value)
            rejected = value
        }
        return rejected?.let { Result.success(it) } ?: Result.failure(IllegalStateException("No stream"))
    }

    private val client by lazy {
        OkHttpClient
            .Builder()
            .proxy(YouTube.proxy)
            .proxyAuthenticator { _, response ->
                YouTube.proxyAuth?.let { auth ->
                    response.request.newBuilder().header("Proxy-Authorization", auth).build()
                } ?: response.request
            }.callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .connectTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    /** Asks YouTube for the first byte of [url]; never throws. */
    suspend fun check(
        url: String,
        headers: Map<String, String>,
    ): Verdict =
        withContext(Dispatchers.IO) {
            try {
                val request =
                    Request
                        .Builder()
                        .url(url)
                        .apply { headers.forEach { (name, value) -> header(name, value) } }
                        .header("Range", "bytes=0-0")
                        .build()
                client.newCall(request).execute().use { verdict(it.code) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                Verdict.UNKNOWN
            }
        }
}
