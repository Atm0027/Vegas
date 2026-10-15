package com.metrolist.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StallRecoveryTest {
    @Test
    fun startOfSong_waitsSixSeconds() = assertEquals(6_000L, StallRecovery.delayMs(0L))

    @Test
    fun midSong_waitsEightSeconds() = assertEquals(8_000L, StallRecovery.delayMs(90_000L))

    @Test
    fun offline_doesNotRefreshStream() =
        assertFalse(StallRecovery.canRefreshStream(isConnected = false, waitingForNetwork = false))

    @Test
    fun waitingForNetwork_doesNotRefreshStream() =
        assertFalse(StallRecovery.canRefreshStream(isConnected = true, waitingForNetwork = true))

    @Test
    fun online_refreshesStream() =
        assertTrue(StallRecovery.canRefreshStream(isConnected = true, waitingForNetwork = false))
}
