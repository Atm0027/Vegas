/**
 * Vega: caps how many downloads run at once. WorkManager starts every eligible CoroutineWorker
 * together, and hundreds of parallel stream resolves exhaust memory and get throttled by YouTube.
 * While a song streams the cap drops to one, so playback is not starved by downloads.
 * Licensed under GPL-3.0
 */

package com.metrolist.music.offline

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

class DownloadSlots(private val maxPermits: Int) {
    private val limit = MutableStateFlow(maxPermits)
    private val running = MutableStateFlow(0)

    fun setLimit(permits: Int) {
        limit.value = permits.coerceIn(1, maxPermits)
    }

    fun setStreaming(streaming: Boolean) = setLimit(if (streaming) 1 else maxPermits)

    suspend fun <T> withSlot(block: suspend () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            while (true) {
                val current = running.value
                if (running.compareAndSet(current, current - 1)) break
            }
        }
    }

    private suspend fun acquire() {
        while (true) {
            val current = running.value
            if (current < limit.value) {
                if (running.compareAndSet(current, current + 1)) return
                continue
            }
            combine(running, limit) { r, l -> r < l }.first { it }
        }
    }

    companion object {
        val shared = DownloadSlots(3)
    }
}
