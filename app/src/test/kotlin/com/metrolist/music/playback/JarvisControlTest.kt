package com.metrolist.music.playback

import androidx.media3.common.Player
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JarvisControlTest {
    private val ownUid = 10_100
    private val jarvisUid = 10_200
    private val jarvisCert = TrustedCallers.hexToBytes(TrustedCallers.JARVIS_CERT_SHA256)

    private fun allowed(uid: Int, packages: Map<Int, List<String>>, certOk: Boolean = true) =
        TrustedCallers.isAllowed(
            callerUid = uid,
            ownUid = ownUid,
            packagesForUid = { packages[it].orEmpty() },
            hasCertificate = { pkg, sha -> certOk && pkg == TrustedCallers.JARVIS_PACKAGE && sha.contentEquals(jarvisCert) },
        )

    @Test
    fun `vegas itself and signed jarvis are allowed`() {
        assertTrue(allowed(ownUid, emptyMap()))
        assertTrue(allowed(jarvisUid, mapOf(jarvisUid to listOf(TrustedCallers.JARVIS_PACKAGE))))
    }

    @Test
    fun `jarvis with another signing key is refused`() {
        assertFalse(allowed(jarvisUid, mapOf(jarvisUid to listOf(TrustedCallers.JARVIS_PACKAGE)), certOk = false))
    }

    @Test
    fun `other apps and unknown callers are refused`() {
        assertFalse(allowed(10_300, mapOf(10_300 to listOf("com.example.other"))))
        assertFalse(allowed(10_300, emptyMap()))
        assertFalse(allowed(-1, emptyMap()))
    }

    @Test
    fun `certificate fingerprint decodes to 32 bytes`() {
        assertEquals(32, jarvisCert.size)
        assertArrayEquals(byteArrayOf(0x1a, 0xfd.toByte()), jarvisCert.copyOfRange(0, 2))
    }

    @Test
    fun `values use the same words as the pc api`() {
        assertEquals(Player.REPEAT_MODE_OFF, VegasCommands.repeatMode("no"))
        assertEquals(Player.REPEAT_MODE_ALL, VegasCommands.repeatMode("lista"))
        assertEquals(Player.REPEAT_MODE_ONE, VegasCommands.repeatMode("una"))
        assertNull(VegasCommands.repeatMode("siempre"))
        assertEquals(false, VegasCommands.playNext(null))
        assertEquals(false, VegasCommands.playNext("final"))
        assertEquals(true, VegasCommands.playNext("siguiente"))
        assertNull(VegasCommands.playNext("principio"))
        assertTrue(VegasCommands.isVegasCommand(VegasCommands.QUEUE_ADD))
        assertFalse(VegasCommands.isVegasCommand("TOGGLE_LIKE"))
        assertEquals(6, VegasCommands.actions.toSet().size)
    }
}
