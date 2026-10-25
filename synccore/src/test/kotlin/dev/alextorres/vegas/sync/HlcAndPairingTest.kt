package dev.alextorres.vegas.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HlcTest {
    @Test
    fun tickAlwaysGrowsEvenIfTheClockGoesBack() {
        var now = 5_000L
        val clock = HlcClock("a") { now }
        val first = clock.tick()
        now = 1_000
        val second = clock.tick()
        assertTrue(second > first)
    }

    @Test
    fun observingARemoteClockMovesAhead() {
        val clock = HlcClock("a") { 1_000 }
        clock.observe(Hlc(9_000, 3, "b"))
        assertTrue(clock.tick() > Hlc(9_000, 3, "b"))
    }

    @Test
    fun deviceBreaksTies() {
        assertTrue(Hlc(1, 0, "b") > Hlc(1, 0, "a"))
    }
}

class PairingTest {
    private val cert = ByteArray(32) { it.toByte() }
    private val nonce = ByteArray(16) { 7 }

    @Test
    fun proofMatchesOnlyWithTheSameCodeAndCertificate() {
        val proof = Pairing.proof("123456", cert, nonce)
        assertTrue(proof.contentEquals(Pairing.proof("123456", cert, nonce)))
        assertFalse(proof.contentEquals(Pairing.proof("123457", cert, nonce)))
        assertFalse(proof.contentEquals(Pairing.proof("123456", cert.copyOf().also { it[0] = 9 }, nonce)))
    }

    @Test
    fun codesAreSixDigitsAndTokensAreLong() {
        assertTrue(Pairing.newCode().matches(Regex("\\d{6}")))
        assertEquals(43, Pairing.newToken().length)
        assertNotEquals(Pairing.newToken(), Pairing.newToken())
    }
}
