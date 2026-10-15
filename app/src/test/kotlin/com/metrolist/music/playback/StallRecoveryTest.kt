package com.metrolist.music.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class StallRecoveryTest {
    @Test
    fun startOfSong_waitsSixSeconds() = assertEquals(6_000L, StallRecovery.delayMs(0L))

    @Test
    fun midSong_waitsEightSeconds() = assertEquals(8_000L, StallRecovery.delayMs(90_000L))
}
