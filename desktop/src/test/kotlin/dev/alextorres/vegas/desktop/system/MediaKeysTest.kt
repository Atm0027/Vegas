package dev.alextorres.vegas.desktop.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaKeysTest {
    @Test
    fun eachMediaKeyMapsToItsAction() {
        assertEquals(MediaKey.PLAY_PAUSE, MediaKey.fromVirtualKey(0xB3))
        assertEquals(MediaKey.NEXT, MediaKey.fromVirtualKey(0xB0))
        assertEquals(MediaKey.PREVIOUS, MediaKey.fromVirtualKey(0xB1))
        assertEquals(MediaKey.STOP, MediaKey.fromVirtualKey(0xB2))
    }

    @Test
    fun otherKeysAreIgnored() {
        assertNull(MediaKey.fromVirtualKey(0x41))
    }

    @Test
    fun hotkeyIdsRoundTrip() {
        MediaKey.entries.forEach { assertEquals(it, MediaKey.fromHotkeyId(it.hotkeyId)) }
    }
}
