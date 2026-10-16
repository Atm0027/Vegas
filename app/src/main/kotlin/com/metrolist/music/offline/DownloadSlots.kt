/**
 * Vega: caps how many downloads run at once. WorkManager starts every eligible CoroutineWorker
 * together, and hundreds of parallel stream resolves exhaust memory and get throttled by YouTube.
 * Licensed under GPL-3.0
 */

package com.metrolist.music.offline

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class DownloadSlots(permits: Int) {
    private val semaphore = Semaphore(permits)

    suspend fun <T> withSlot(block: suspend () -> T): T = semaphore.withPermit { block() }

    companion object {
        val shared = DownloadSlots(3)
    }
}
