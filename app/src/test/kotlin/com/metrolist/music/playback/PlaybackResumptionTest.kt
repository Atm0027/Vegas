package com.metrolist.music.playback

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackResumptionTest {
    @Test
    fun queueAlreadyLoaded_resumesAtOnce() = runBlocking {
        assertTrue(PlaybackResumption.awaitQueue(restorePending = { true }, hasItems = { true }, timeoutMs = 0))
    }

    @Test
    fun waitsForTheSavedQueueStillBeingRestored() = runBlocking {
        val readyAt = System.currentTimeMillis() + 100
        assertTrue(
            PlaybackResumption.awaitQueue(
                restorePending = { true },
                hasItems = { System.currentTimeMillis() >= readyAt },
                timeoutMs = 2_000,
                pollMs = 10,
            ),
        )
    }

    @Test
    fun nothingSaved_doesNotWait() = runBlocking {
        val start = System.currentTimeMillis()
        assertFalse(PlaybackResumption.awaitQueue(restorePending = { false }, hasItems = { false }, timeoutMs = 5_000))
        assertTrue(System.currentTimeMillis() - start < 1_000)
    }

    @Test
    fun brokenRestore_givesUpAfterTheTimeout() = runBlocking {
        assertFalse(PlaybackResumption.awaitQueue(restorePending = { true }, hasItems = { false }, timeoutMs = 100, pollMs = 10))
    }
}
