package com.metrolist.music.utils

import com.metrolist.music.utils.potoken.PoTokenMemory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PoTokenMemoryTest {
    @Test
    fun `no pressure allows a new webview`() {
        assertFalse(PoTokenMemory.skipNewWebView(lowMemory = false, pressureAt = null, now = 1_000))
    }

    @Test
    fun `system low memory skips the webview`() {
        assertTrue(PoTokenMemory.skipNewWebView(lowMemory = true, pressureAt = null, now = 1_000))
    }

    @Test
    fun `recent pressure skips the webview until the cooldown ends`() {
        assertTrue(PoTokenMemory.skipNewWebView(false, pressureAt = 1_000, now = 1_000 + PoTokenMemory.COOLDOWN_MS - 1))
        assertFalse(PoTokenMemory.skipNewWebView(false, pressureAt = 1_000, now = 1_000 + PoTokenMemory.COOLDOWN_MS))
    }
}
