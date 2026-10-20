/**
 * Vegas: a car (Android Auto or its Bluetooth play button) can start the service and ask it to play
 * before the saved queue has been put back into the player; play must wait for it instead of doing nothing.
 * Licensed under GPL-3.0
 */

package com.metrolist.music.playback

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

object PlaybackResumption {
    const val RESTORE_WAIT_MS = 5_000L

    /** True when the player holds a queue to resume, waiting up to [timeoutMs] while one is being restored. */
    suspend fun awaitQueue(
        restorePending: () -> Boolean,
        hasItems: () -> Boolean,
        timeoutMs: Long = RESTORE_WAIT_MS,
        pollMs: Long = 50,
    ): Boolean {
        if (hasItems()) return true
        if (!restorePending()) return false
        return withTimeoutOrNull(timeoutMs) {
            while (!hasItems()) delay(pollMs)
            true
        } ?: false
    }
}
