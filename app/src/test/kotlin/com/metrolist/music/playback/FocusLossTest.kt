package com.metrolist.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusLossTest {
    @Test
    fun pausesOnFocusLossByDefault() {
        assertEquals(FocusLoss.Action.PAUSE, FocusLoss.onLoss(keepPlaying = false))
    }

    @Test
    fun keepsPlayingWhenTheSettingIsOn() {
        assertEquals(FocusLoss.Action.KEEP_PLAYING, FocusLoss.onLoss(keepPlaying = true))
    }

    @Test
    fun staysInForegroundForTenMinutesAfterAFocusPause() {
        val pausedAt = 1_000_000L
        assertTrue(FocusLoss.holdForeground(pausedAt, now = pausedAt + 9 * 60_000))
        assertFalse(FocusLoss.holdForeground(pausedAt, now = pausedAt + 10 * 60_000 + 1))
    }

    @Test
    fun noHoldWithoutAFocusPause() {
        assertFalse(FocusLoss.holdForeground(pausedAt = null, now = 5_000))
    }
}
