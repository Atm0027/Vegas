/**
 * Vegas: bound stream resolution time.
 * Licensed under GPL-3.0
 */

package com.metrolist.music.playback

import kotlinx.coroutines.withTimeoutOrNull
import java.net.SocketTimeoutException

/**
 * Runs a stream resolution with an upper time bound. A hung resolution (player API, PoToken
 * WebView, client fallbacks) becomes a [SocketTimeoutException], which MusicService already
 * treats as a recoverable network error instead of leaving the player buffering forever.
 */
suspend fun <T> resolveWithTimeout(
    timeoutMs: Long,
    mediaId: String,
    block: suspend () -> Result<T>,
): Result<T> =
    withTimeoutOrNull(timeoutMs) { block() }
        ?: Result.failure(SocketTimeoutException("Stream resolution timed out for $mediaId"))
