/**
 * Vega: stall recovery timing.
 * Licensed under GPL-3.0
 */

package com.metrolist.music.playback

object StallRecovery {
    private const val START_WINDOW_MS = 5_000L

    /** How long a buffering stall may last before the stream is refreshed. */
    fun delayMs(positionMs: Long): Long = if (positionMs < START_WINDOW_MS) 6_000L else 8_000L

    /** Offline stalls belong to the network-wait logic; refreshing would blame a healthy client. */
    fun canRefreshStream(
        isConnected: Boolean,
        waitingForNetwork: Boolean,
    ): Boolean = isConnected && !waitingForNetwork
}
