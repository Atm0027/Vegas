package com.metrolist.music.offline

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class DownloadSlotsTest {
    @Test
    fun manyDownloads_runAtMostThreeAtOnce() = runBlocking {
        val slots = DownloadSlots(3)
        val running = AtomicInteger()
        val peak = AtomicInteger()
        (1..20).map {
            async {
                slots.withSlot {
                    peak.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                    delay(100)
                    running.decrementAndGet()
                }
            }
        }.awaitAll()
        assertEquals(3, peak.get())
    }
}
