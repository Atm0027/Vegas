package dev.alextorres.vegas.desktop.offline

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

    @Test
    fun whileStreaming_onlyOneDownloadStarts_andTheRestResumeAfter() = runBlocking {
        val slots = DownloadSlots(3)
        slots.setLimit(1)
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val jobs = (1..6).map {
            async {
                slots.withSlot {
                    peak.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                    delay(100)
                    running.decrementAndGet()
                }
            }
        }
        delay(250)
        assertEquals(1, peak.get())
        slots.setLimit(3)
        jobs.awaitAll()
        assertEquals(3, peak.get())
    }
}
