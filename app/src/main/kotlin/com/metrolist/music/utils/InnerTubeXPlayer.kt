package com.metrolist.music.utils

import android.content.Context
import android.net.ConnectivityManager
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.Thumbnail
import com.metrolist.innertube.models.Thumbnails
import com.metrolist.innertube.models.response.PlayerResponse
import com.metrolist.innertubex.InnerTubeLogLevel
import com.metrolist.innertubex.InnerTube as InnerTubeX
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality as InnerTubeXAudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.ExtractedStream
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.PoTokenResult
import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.TokenProviderCapabilities
import com.metrolist.innertubex.extraction.YtConfigParser
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.extraction.generateClientPlaybackNonce
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import com.metrolist.music.constants.AudioQuality
import com.metrolist.music.playback.StreamProbe
import com.metrolist.music.playback.WidelyRefusedClients
import com.metrolist.music.utils.potoken.PoTokenGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import kotlin.time.Clock

/** The sole stream extraction entry point for the Android app. */
object InnerTubeXPlayer {
    private const val TAG = "InnerTubeXPlayer"
    private const val STREAM_CLIENT_FAILURE_TTL_MS = 5 * 60 * 1000L
    private const val DEFAULT_STREAM_TTL_SECONDS = 5 * 60

    @Volatile
    private var applicationContext: Context? = null

    @Volatile
    private var currentBundle: ExtractionBundle? = null

    private val bundleMutex = Mutex()
    private val streamClientFailures = java.util.concurrent.ConcurrentHashMap<String, FailedStreamClients>()
    private val widelyRefused = WidelyRefusedClients()
    private val alternativeUploads = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val withoutAlternativeUpload = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val knownAgeRestricted = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var ageVerificationSession: AgeVerificationSession? = null

    @Volatile
    private var ageVerificationBundle: AgeVerificationBundle? = null

    @Volatile
    private var playbackAccountOptions = PlaybackAccountOptions()

    @Synchronized
    fun initialize(context: Context) {
        if (applicationContext == null) applicationContext = context.applicationContext
    }

    suspend fun prewarm() = bundle().extractor.prewarm()

    suspend fun playerResponseForPlayback(
        videoId: String,
        playlistId: String? = null,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
        contentHints: ContentHints = ContentHints(),
        allowBoundedRange: Boolean = true,
        qualityOverride: InnerTubeXAudioQuality? = null,
    ): Result<PlaybackData> {
        suspend fun resolve(
            id: String,
            extractor: InnerTubeExtractor? = null,
            hints: ContentHints = contentHints,
        ) = StreamProbe.resolveVerified(
            resolve = {
                resolvePlayback(
                    id,
                    playlistId,
                    audioQuality,
                    connectivityManager,
                    hints,
                    allowBoundedRange,
                    qualityOverride,
                    extractor ?: bundle().extractor,
                )
            },
            probe = { StreamProbe.check(it.streamUrl, it.streamHeaders, it.format.contentLength) },
            onRejected = {
                Timber.tag(TAG).i("$id: ${it.streamClient} stream refused (403), trying another client")
                markStreamClientFailed(id, it.streamClient)
            },
        )

        suspend fun resolveWithAgeVerification(): Result<PlaybackData>? {
            val extractor = ageVerificationExtractor() ?: return null
            Timber.tag(TAG).i("age-restricted $videoId: retrying with the age verification account")
            return resolve(videoId, extractor, contentHints.copy(isAgeRestricted = true))
                .map(PlaybackData::withoutTracking)
                .onFailure { Timber.tag(TAG).w("age-restricted $videoId: age verification failed: ${it.message}") }
        }

        val accountOptions = playbackAccountOptions
        if (accountOptions.always || accountOptions.premium) {
            ageVerificationExtractor()?.let { extractor ->
                resolve(videoId, extractor, contentHints.withPremium(accountOptions.premium))
                    .map(PlaybackData::withoutTracking)
                    .onSuccess { return Result.success(it) }
                    .onFailure { Timber.tag(TAG).w("$videoId: account playback failed, trying without: ${it.message}") }
            }
        }
        if (videoId in knownAgeRestricted) resolveWithAgeVerification()?.onSuccess { return Result.success(it) }
        alternativeUploads[videoId]?.let { alternativeId ->
            resolve(alternativeId).onSuccess { return Result.success(it) }
            alternativeUploads.remove(videoId, alternativeId)
        }
        val result = resolve(videoId)
        val error = result.exceptionOrNull() as? StreamResolveException ?: return result
        when (error.reason) {
            StreamResolveException.Reason.AGE_RESTRICTED ->
                if (knownAgeRestricted.add(videoId)) resolveWithAgeVerification()?.onSuccess { return Result.success(it) }
            // Deleted, private, or blocked in this country: another upload of the song may still play.
            StreamResolveException.Reason.UNAVAILABLE -> Unit
            else -> return result
        }
        if (videoId in withoutAlternativeUpload) return result
        return resolveAlternativeUpload(videoId) { resolve(it) }
            ?: result.also { withoutAlternativeUpload += videoId }
    }

    /**
     * The Google session the user signed in with only to pass YouTube's age check. It lives in its
     * own InnerTubeX session, so the rest of Vegas (search, browse, home, library) never sends it.
     */
    fun setAgeVerificationSession(
        session: AgeVerificationSession?,
        options: PlaybackAccountOptions = PlaybackAccountOptions(),
    ) {
        playbackAccountOptions = if (session == null) PlaybackAccountOptions() else options
        if (ageVerificationSession == session) return
        ageVerificationSession = session
        ageVerificationBundle = null
        withoutAlternativeUpload.clear()
    }

    private suspend fun ageVerificationExtractor(): InnerTubeExtractor? {
        val session = ageVerificationSession ?: return null
        val transport = YouTube.extractionTransport()
        ageVerificationBundle
            ?.takeIf { it.session == session && it.transportGeneration == transport.generation }
            ?.let { return it.extractor }
        val main = bundle()
        return bundleMutex.withLock {
            ageVerificationBundle
                ?.takeIf { it.session == session && it.transportGeneration == transport.generation }
                ?.let { return@withLock it.extractor }
            val innerTube =
                InnerTubeX(transport.httpClient).apply {
                    locale = transport.innerTube.locale
                    replaceSession(
                        cookie = session.cookie,
                        visitorData = session.visitorData,
                        dataSyncId = null,
                        authUser = "0",
                        useLoginForBrowse = false,
                    )
                }
            val remoteStore = RemotePlayerConfigStore(transport.httpClient, configRepository, logger)
            val extractor =
                InnerTubeExtractor(
                    configParser =
                        YtConfigParserImpl(transport.httpClient, innerTube, remoteStore, logger)
                            .withEmbeddedConfigFallback()
                            .withSignedOutPlayer(),
                    cipherService = main.cipherService,
                    innerTube = innerTube,
                    tokenProvider = tokenProvider,
                    logger = logger,
                )
            AgeVerificationBundle(session, transport.generation, extractor).also { ageVerificationBundle = it }.extractor
        }
    }

    /**
     * Title, artists and length of a song saved in Vegas, for uploads YouTube no longer describes
     * (deleted or private). Set by the music service.
     */
    /** Called when [candidate] plays in place of an upload that cannot; set by the music service. */
    @Volatile
    var onAlternativeUpload: ((originalId: String, candidate: String) -> Unit)? = null

    @Volatile
    var savedSongMetadata: (suspend (String) -> AgeRestrictedFallback.Original?)? = null

    /** Replaces an upload that cannot play (age-restricted, deleted, private, blocked here) by another upload of the same song. */
    private suspend fun resolveAlternativeUpload(
        videoId: String,
        resolve: suspend (String) -> Result<PlaybackData>,
    ): Result<PlaybackData>? {
        val original =
            YouTube.queue(videoIds = listOf(videoId)).getOrNull()?.firstOrNull()?.let { song ->
                AgeRestrictedFallback.Original(
                    videoId = videoId,
                    title = song.title,
                    artists = song.artists.map { it.name },
                    durationSeconds = song.duration,
                )
            } ?: savedSongMetadata?.invoke(videoId)
            ?: return null
        val query = AgeRestrictedFallback.query(original)
        val results =
            listOf(YouTube.SearchFilter.FILTER_SONG, YouTube.SearchFilter.FILTER_VIDEO).flatMap { filter ->
                YouTube.search(query, filter).getOrNull()?.items.orEmpty().filterIsInstance<SongItem>()
            }
        val candidates = AgeRestrictedFallback.candidates(original, results)
        Timber.tag(TAG).i("unplayable $videoId: trying alternative uploads $candidates")
        for (candidate in candidates) {
            val playback =
                resolve(candidate).getOrElse { error ->
                    Timber.tag(TAG).i("unplayable $videoId: alternative $candidate failed: ${error.message}")
                    null
                } ?: continue
            Timber.tag(TAG).i("unplayable $videoId: playing alternative upload $candidate")
            alternativeUploads[videoId] = candidate
            onAlternativeUpload?.invoke(videoId, candidate)
            return Result.success(playback)
        }
        return null
    }

    private suspend fun resolvePlayback(
        videoId: String,
        playlistId: String?,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
        contentHints: ContentHints,
        allowBoundedRange: Boolean,
        qualityOverride: InnerTubeXAudioQuality?,
        extractor: InnerTubeExtractor,
    ): Result<PlaybackData> =
        try {
            val hints =
                contentHints.copy(
                    isUploaded =
                        contentHints.isUploaded == true ||
                            playlistId == "MLPT" ||
                            playlistId?.contains("MLPT") == true,
                ).withStreamCapabilities(
                    allowHls = false,
                    allowSabr = false,
                    allowBoundedRange = allowBoundedRange,
                )
            val excludedClients = failedStreamClients(videoId)
            val refusedEverywhere = widelyRefused.clients(System.currentTimeMillis()) - excludedClients

            suspend fun extract(excluded: Set<String>) =
                requireNotNull(
                    extractor.extract(
                        videoId = videoId,
                        hints = hints,
                        excludedClients = excluded,
                        audioQuality = qualityOverride ?: audioQuality.toInnerTubeX(connectivityManager),
                        clientPlaybackNonce = generateClientPlaybackNonce(),
                    ),
                ) { "InnerTubeX returned no playable stream" }
            val stream =
                if (refusedEverywhere.isEmpty()) {
                    extract(excludedClients)
                } else {
                    // Skip clients YouTube is refusing on other songs; if nothing else plays, try them anyway.
                    try {
                        extract(excludedClients + refusedEverywhere)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Timber.tag(TAG).i("$videoId: no stream without $refusedEverywhere, trying them again")
                        extract(excludedClients)
                    }
                }
            check(stream.sabrBootstrap == null) { "SABR is not supported by this playback engine" }
            Result.success(stream.toPlaybackData())
        } catch (error: CancellationException) {
            throw error
        } catch (error: StreamResolveException) {
            val cause = error.cause
            Result.failure(
                if (error.reason == StreamResolveException.Reason.NETWORK && cause != null) {
                    cause
                } else {
                    error
                },
            )
        } catch (error: Exception) {
            Result.failure(error)
        }

    internal fun markStreamClientFailed(
        videoId: String,
        clientName: String,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        streamClientFailures.compute(videoId) { _, failures ->
            FailedStreamClients(failures?.clientNames.orEmpty() + clientName, nowMs)
        }
        widelyRefused.record(clientName, videoId, nowMs)
    }

    fun clearStreamClientFailures() {
        streamClientFailures.clear()
        widelyRefused.clear()
    }

    internal fun widelyRefusedClients(nowMs: Long = System.currentTimeMillis()): Set<String> = widelyRefused.clients(nowMs)

    suspend fun refreshAfterStreamRejection(): Boolean = bundle().cipherService.refreshAfterStreamRejection()

    internal fun failedStreamClients(
        videoId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Set<String> {
        val failures = streamClientFailures[videoId] ?: return emptySet()
        if ((nowMs - failures.failedAtMs) !in 0 until STREAM_CLIENT_FAILURE_TTL_MS) {
            streamClientFailures.remove(videoId, failures)
            return emptySet()
        }
        return failures.clientNames
    }

    private suspend fun bundle(): ExtractionBundle {
        val transport = YouTube.extractionTransport()
        currentBundle?.takeIf { it.transportGeneration == transport.generation }?.let { return it }
        return bundleMutex.withLock {
            val latestTransport = YouTube.extractionTransport()
            currentBundle?.takeIf { it.transportGeneration == latestTransport.generation }?.let { return@withLock it }
            try {
                currentBundle?.cipherService?.dispose()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                logger.log(
                    com.metrolist.innertubex.InnerTubeLogEvent(
                        level = InnerTubeLogLevel.WARN,
                        tag = TAG,
                        message = "old cipher disposal failed",
                        details = mapOf("exceptionType" to (error::class.simpleName ?: "unknown")),
                    ),
                )
            }

            val remoteStore = RemotePlayerConfigStore(latestTransport.httpClient, configRepository, logger)
            val cipherService = YouTubeCipherService(latestTransport.httpClient, remoteStore, logger)
            val extractor =
                InnerTubeExtractor(
                    configParser =
                        YtConfigParserImpl(
                            latestTransport.httpClient,
                            latestTransport.innerTube,
                            remoteStore,
                            logger,
                        ).withEmbeddedConfigFallback(),
                    cipherService = cipherService,
                    innerTube = latestTransport.innerTube,
                    tokenProvider = tokenProvider,
                    logger = logger,
                )
            ExtractionBundle(latestTransport.generation, cipherService, extractor).also { currentBundle = it }
        }
    }

    private val configRepository: PlayerConfigRepository by lazy {
        AndroidPlayerConfigRepository(requireNotNull(applicationContext) { "InnerTubeXPlayer is not initialized" })
    }

    private val poTokenGeneratorLazy =
        lazy { PoTokenGenerator(requireNotNull(applicationContext) { "InnerTubeXPlayer is not initialized" }) }
    private val poTokenGenerator: PoTokenGenerator by poTokenGeneratorLazy

    /** Frees the PoToken WebView after a long pause; the next stream request recreates it. */
    suspend fun releasePoToken() {
        if (poTokenGeneratorLazy.isInitialized()) poTokenGenerator.close()
    }

    /** Frees the PoToken WebView when Android asks for memory. */
    suspend fun onMemoryPressure() {
        if (poTokenGeneratorLazy.isInitialized()) poTokenGenerator.onMemoryPressure()
    }

    private val tokenProvider =
        object : TokenProvider {
            override val capabilities =
                TokenProviderCapabilities(
                    providers = setOf(PoTokenProviderKind.WEB_BOTGUARD),
                    usesWebView = true,
                )

            override suspend fun getPoToken(
                videoId: String,
                visitorData: String,
                cookie: String?,
            ): PoTokenResult? =
                poTokenGenerator.getWebClientPoToken(videoId, visitorData)?.let { token ->
                    PoTokenResult(
                        playerRequestToken = token.playerRequestPoToken,
                        streamingDataToken = token.streamingDataPoToken,
                        visitorData = visitorData,
                    )
                }

            override suspend fun close() {
                poTokenGenerator.close()
            }
        }

    private val logger =
        InnerTubeLogger { event ->
            val details = event.details.entries.joinToString(prefix = " [", postfix = "]") { "${it.key}=${it.value}" }
            val message = event.message + details.takeUnless { event.details.isEmpty() }.orEmpty()
            when (event.level) {
                InnerTubeLogLevel.DEBUG -> Timber.tag(event.tag).d(message)
                InnerTubeLogLevel.INFO -> Timber.tag(event.tag).i(message)
                InnerTubeLogLevel.WARN -> Timber.tag(event.tag).w(message)
                InnerTubeLogLevel.ERROR -> Timber.tag(event.tag).e(message)
            }
        }

    class PlaybackData(
        val audioConfig: PlayerResponse.PlayerConfig.AudioConfig?,
        val videoDetails: PlayerResponse.VideoDetails?,
        val playbackTracking: PlayerResponse.PlaybackTracking?,
        val format: PlayerResponse.StreamingData.Format,
        val streamUrl: String,
        val streamExpiresInSeconds: Int,
        val streamClient: String,
        val streamHeaders: Map<String, String>,
        val requireBoundedRange: Boolean,
        val rangeChunkSizeBytes: Long,
        val useRangeChunks: Boolean,
    ) {
        /** Streams fetched with the playback account are never reported to YouTube's watch history. */
        fun withoutTracking() =
            PlaybackData(
                audioConfig, videoDetails, null, format, streamUrl, streamExpiresInSeconds, streamClient,
                streamHeaders, requireBoundedRange, rangeChunkSizeBytes, useRangeChunks,
            )
    }

    private data class ExtractionBundle(
        val transportGeneration: Long,
        val cipherService: YouTubeCipherService,
        val extractor: InnerTubeExtractor,
    )

    data class AgeVerificationSession(
        val cookie: String,
        val visitorData: String?,
    ) {
        override fun toString() = "AgeVerificationSession(<redacted>)"
    }

    /** Extra, opt-in uses of the playback account; age-restricted tracks always use it. */
    data class PlaybackAccountOptions(
        val premium: Boolean = false,
        val always: Boolean = false,
    )

    private class AgeVerificationBundle(
        val session: AgeVerificationSession,
        val transportGeneration: Long,
        val extractor: InnerTubeExtractor,
    )

    private data class FailedStreamClients(
        val clientNames: Set<String>,
        val failedAtMs: Long,
    )

    private class AndroidPlayerConfigRepository(context: Context) : PlayerConfigRepository {
        private val preferences = context.getSharedPreferences("innertubex_player_config", Context.MODE_PRIVATE)

        override val enabled: Boolean = true
        override val sourceUrl: String = PLAYER_CONFIG_URL
        override val defaultSourceUrl: String = PLAYER_CONFIG_URL
        override var cachedJson: String
            get() = preferences.getString("json", "").orEmpty()
            set(value) = preferences.edit().putString("json", value).apply()
        override var cachedAtMs: Long
            get() = preferences.getLong("cached_at_ms", 0L)
            set(value) = preferences.edit().putLong("cached_at_ms", value).apply()
        override var cachedSourceUrl: String
            get() = preferences.getString("source_url", "").orEmpty()
            set(value) = preferences.edit().putString("source_url", value).apply()
        override var cachedEtag: String
            get() = preferences.getString("etag", "").orEmpty()
            set(value) = preferences.edit().putString("etag", value).apply()

        private companion object {
            const val PLAYER_CONFIG_URL =
                "https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/library/src/main/assets/player_configs.json"
        }
    }

    /**
     * The signed-in watch page can name a player script the cipher configs do not cover yet, which
     * leaves every signed audio URL unsolved. Keep the session's config but take the player script
     * (and its signature timestamp) from the signed-out page, which the cipher service supports.
     */
    private fun YtConfigParser.withSignedOutPlayer(): YtConfigParser =
        object : YtConfigParser by this {
            override suspend fun fetchConfig(
                videoId: String,
                useLoginCookies: Boolean,
            ): com.metrolist.innertubex.extraction.PlayerConfig {
                val config = this@withSignedOutPlayer.fetchConfig(videoId, useLoginCookies)
                if (!useLoginCookies) return config
                val signedOut =
                    try {
                        this@withSignedOutPlayer.fetchConfig(videoId, useLoginCookies = false)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        return config
                    }
                if (signedOut.playerUrl.isBlank() || signedOut.playerUrl == config.playerUrl) return config
                Timber.tag(TAG).i("account config: using the signed-out player script")
                return config.copy(playerUrl = signedOut.playerUrl, signatureTimestamp = signedOut.signatureTimestamp)
            }
        }

    internal fun YtConfigParser.withEmbeddedConfigFallback(): YtConfigParser =
        object : YtConfigParser by this {
            override suspend fun fetchConfig(
                videoId: String,
                useLoginCookies: Boolean,
            ) =
                try {
                    this@withEmbeddedConfigFallback.fetchConfig(videoId, useLoginCookies)
                } catch (_: IllegalStateException) {
                    this@withEmbeddedConfigFallback.fetchEmbeddedConfig(videoId, useLoginCookies = false)
                }
        }

    private fun AudioQuality.toInnerTubeX(connectivityManager: ConnectivityManager): InnerTubeXAudioQuality =
        when (this) {
            AudioQuality.HIGH -> InnerTubeXAudioQuality.HIGH
            AudioQuality.LOW -> InnerTubeXAudioQuality.LOW
            AudioQuality.AUTO ->
                if (connectivityManager.isActiveNetworkMetered) {
                    InnerTubeXAudioQuality.LOW
                } else {
                    InnerTubeXAudioQuality.AUTO
                }
        }

    private fun ExtractedStream.toPlaybackData(): PlaybackData {
        val metadata = mediaMetadata
        val tracking = playbackTracking
        val fullMimeType =
            if (codecs.isNullOrBlank()) {
                mimeType.orEmpty()
            } else {
                "${mimeType.orEmpty()}; codecs=\"$codecs\""
            }
        return PlaybackData(
            audioConfig =
                if (loudnessDb != null || perceptualLoudnessDb != null) {
                    PlayerResponse.PlayerConfig.AudioConfig(loudnessDb, perceptualLoudnessDb)
                } else {
                    null
                },
            videoDetails =
                metadata?.let {
                    PlayerResponse.VideoDetails(
                        videoId = videoId,
                        title = it.title,
                        author = it.author,
                        channelId = it.channelId.orEmpty(),
                        lengthSeconds = it.durationSeconds?.toString().orEmpty(),
                        musicVideoType = it.musicVideoType,
                        viewCount = it.viewCount,
                        thumbnail =
                            Thumbnails(
                                it.thumbnails.map { thumbnail ->
                                    Thumbnail(thumbnail.url, thumbnail.width, thumbnail.height)
                                },
                            ),
                    )
                },
            playbackTracking =
                tracking?.let {
                    PlayerResponse.PlaybackTracking(
                        videostatsPlaybackUrl =
                            it.playbackUrl?.let(PlayerResponse.PlaybackTracking::VideostatsPlaybackUrl),
                        videostatsWatchtimeUrl =
                            it.watchtimeUrl?.let(PlayerResponse.PlaybackTracking::VideostatsWatchtimeUrl),
                        atrUrl = null,
                    )
                },
            format =
                PlayerResponse.StreamingData.Format(
                    itag = itag,
                    url = audioUrl,
                    mimeType = fullMimeType,
                    bitrate = bitrate ?: 0,
                    width = null,
                    height = null,
                    contentLength = contentLengthBytes,
                    quality = "",
                    fps = null,
                    qualityLabel = null,
                    averageBitrate = bitrate,
                    audioQuality = null,
                    approxDurationMs = metadata?.durationSeconds?.times(1000)?.toString(),
                    audioSampleRate = sampleRate,
                    audioChannels = null,
                    loudnessDb = loudnessDb,
                    lastModified = null,
                    signatureCipher = null,
                    cipher = null,
                    audioTrack = null,
                ),
            streamUrl = audioUrl,
            streamExpiresInSeconds =
                expiresAt
                    ?.let { ((it.toEpochMilliseconds() - Clock.System.now().toEpochMilliseconds()) / 1000L).toInt() }
                    ?.coerceAtLeast(1)
                    ?: DEFAULT_STREAM_TTL_SECONDS,
            streamClient = clientName,
            streamHeaders = headers,
            requireBoundedRange = this.requireBoundedRange,
            rangeChunkSizeBytes = this.rangeChunkSizeBytes,
            useRangeChunks = this.useRangeChunks,
        )
    }
}
