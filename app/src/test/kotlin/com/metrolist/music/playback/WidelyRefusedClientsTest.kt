package com.metrolist.music.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class WidelyRefusedClientsTest {
    @Test
    fun clientRefusedOnTwoSongs_isSkippedForAll() {
        val refused = WidelyRefusedClients(minSongs = 2, windowMs = 10_000L)
        refused.record("WEB_REMIX", "a", nowMs = 1_000L)
        assertEquals(emptySet<String>(), refused.clients(nowMs = 1_000L))
        refused.record("WEB_REMIX", "a", nowMs = 2_000L)
        assertEquals(emptySet<String>(), refused.clients(nowMs = 2_000L))
        refused.record("WEB_REMIX", "b", nowMs = 3_000L)
        refused.record("WEB_CREATOR", "b", nowMs = 3_000L)
        assertEquals(setOf("WEB_REMIX"), refused.clients(nowMs = 3_000L))
    }

    @Test
    fun oldRefusals_expire() {
        val refused = WidelyRefusedClients(minSongs = 2, windowMs = 10_000L)
        refused.record("WEB_REMIX", "a", nowMs = 1_000L)
        refused.record("WEB_REMIX", "b", nowMs = 5_000L)
        assertEquals(setOf("WEB_REMIX"), refused.clients(nowMs = 10_500L))
        assertEquals(emptySet<String>(), refused.clients(nowMs = 11_000L))
        refused.clear()
        assertEquals(emptySet<String>(), refused.clients(nowMs = 5_000L))
    }

    @Test
    fun exportedRefusals_surviveARestart() {
        val before = WidelyRefusedClients(minSongs = 2, windowMs = 10_000L)
        before.record("WEB_REMIX", "a", nowMs = 1_000L)
        before.record("WEB_REMIX", "b", nowMs = 2_000L)
        val after = WidelyRefusedClients(minSongs = 2, windowMs = 10_000L)
        after.restore(before.export())
        assertEquals(setOf("WEB_REMIX"), after.clients(nowMs = 3_000L))
        after.restore("")
        after.restore("broken line")
        assertEquals(setOf("WEB_REMIX"), after.clients(nowMs = 3_000L))
    }
}
