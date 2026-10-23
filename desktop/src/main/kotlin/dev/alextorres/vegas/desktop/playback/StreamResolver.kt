/**
 * Vegas desktop: turns a video id into a playable audio URL. Mirrors the Android InnerTubeXPlayer
 * without a PO-token provider (the clients InnerTubeX picks on the JVM do not need one).
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.desktop.playback

import com.metrolist.innertube.YouTube
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.YtConfigParser
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.extraction.generateClientPlaybackNonce
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

data class ResolvedStream(
    val videoId: String,
    val url: String,
    val headers: Map<String, String>,
    val mimeType: String,
    val expiresAtMs: Long,
    val client: String,
    val loudnessDb: Double?,
    /** From YouTube's metadata; used when the decoder cannot tell the length. */
    val durationMs: Long? = null,
)

class StreamResolver(
    private val extract: suspend (videoId: String, excluded: Set<String>) -> ResolvedStream,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Failed(val clients: Set<String>, val atMs: Long)

    private val cache = ConcurrentHashMap<String, ResolvedStream>()
    private val failures = ConcurrentHashMap<String, Failed>()

    suspend fun resolve(videoId: String): Result<ResolvedStream> {
        cache[videoId]?.takeIf { it.expiresAtMs - now() > REUSE_MARGIN_MS }?.let { return Result.success(it) }
        return try {
            Result.success(extract(videoId, excluded(videoId)).also { cache[videoId] = it })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun markFailed(videoId: String, client: String) {
        cache.remove(videoId)
        failures.compute(videoId) { _, old -> Failed(excluded(videoId, old) + client, now()) }
    }

    fun invalidate(videoId: String) {
        cache.remove(videoId)
    }

    private fun excluded(videoId: String, failed: Failed? = failures[videoId]): Set<String> =
        failed?.takeIf { now() - it.atMs in 0 until STREAM_CLIENT_FAILURE_TTL_MS }?.clients.orEmpty()

    companion object {
        const val REUSE_MARGIN_MS = 60_000L
        const val STREAM_CLIENT_FAILURE_TTL_MS = 300_000L
        private const val DEFAULT_STREAM_TTL_MS = 5 * 60_000L

        fun live(quality: () -> AudioQuality = { AudioQuality.HIGH }): StreamResolver {
            var extractor: Pair<Long, InnerTubeExtractor>? = null
            fun extractor(): InnerTubeExtractor {
                val transport = YouTube.extractionTransport()
                extractor?.takeIf { it.first == transport.generation }?.let { return it.second }
                val store = RemotePlayerConfigStore(transport.httpClient, PlayerConfigRepository.disabled())
                val cipher = YouTubeCipherService(transport.httpClient, store)
                val parser = YtConfigParserImpl(transport.httpClient, transport.innerTube, store).withEmbeddedConfigFallback()
                return InnerTubeExtractor(parser, cipher, transport.innerTube).also { extractor = transport.generation to it }
            }
            return StreamResolver(extract = { id, excluded ->
                val stream = requireNotNull(
                    extractor().extract(
                        videoId = id,
                        hints = ContentHints().withStreamCapabilities(allowHls = false, allowSabr = false, allowBoundedRange = true),
                        excludedClients = excluded,
                        audioQuality = quality(),
                        clientPlaybackNonce = generateClientPlaybackNonce(),
                    ),
                ) { "No playable stream" }
                check(stream.sabrBootstrap == null) { "SABR is not supported" }
                ResolvedStream(
                    videoId = id,
                    url = stream.audioUrl,
                    headers = stream.headers,
                    mimeType = stream.mimeType.orEmpty(),
                    expiresAtMs = stream.expiresAt?.toEpochMilliseconds() ?: (System.currentTimeMillis() + DEFAULT_STREAM_TTL_MS),
                    client = stream.clientName,
                    loudnessDb = stream.loudnessDb,
                    durationMs = stream.mediaMetadata?.durationSeconds?.let { it * 1000L },
                )
            })
        }

        private fun YtConfigParser.withEmbeddedConfigFallback(): YtConfigParser =
            object : YtConfigParser by this {
                override suspend fun fetchConfig(videoId: String, useLoginCookies: Boolean) =
                    try {
                        this@withEmbeddedConfigFallback.fetchConfig(videoId, useLoginCookies)
                    } catch (_: IllegalStateException) {
                        this@withEmbeddedConfigFallback.fetchEmbeddedConfig(videoId, useLoginCookies = false)
                    }
            }
    }
}
