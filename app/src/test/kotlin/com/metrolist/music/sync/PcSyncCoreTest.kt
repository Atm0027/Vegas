package com.metrolist.music.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.alextorres.vegas.sync.AppliedChanges
import dev.alextorres.vegas.sync.Changes
import dev.alextorres.vegas.sync.HlcClock
import dev.alextorres.vegas.sync.InMemorySyncStore
import dev.alextorres.vegas.sync.LibraryAdapter
import dev.alextorres.vegas.sync.LibrarySnapshot
import dev.alextorres.vegas.sync.LocalEntry
import dev.alextorres.vegas.sync.LocalPlaylist
import dev.alextorres.vegas.sync.PairResponse
import dev.alextorres.vegas.sync.SongMeta
import dev.alextorres.vegas.sync.SyncEngine
import dev.alextorres.vegas.sync.SyncRequest
import dev.alextorres.vegas.sync.SyncResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

private class PhoneLibrary : LibraryAdapter {
    var songs = listOf("a")
    override suspend fun snapshot() =
        LibrarySnapshot(listOf(LocalPlaylist("LP1", "Rock", false, 0, songs.mapIndexed { i, s -> LocalEntry("$i-$s", SongMeta(s, s, "x")) })), emptyMap())
    override suspend fun apply(changes: AppliedChanges) = emptyMap<String, String>()
}

private class FakeTransport : SyncTransport {
    var failNext = false
    val received = mutableListOf<Changes>()
    var pairedWith: String? = null

    override suspend fun pair(pc: FoundPc, code: String, deviceId: String, deviceName: String): Pair<PairResponse, ByteArray> {
        pairedWith = code
        return PairResponse("token-123", pc.serverId, pc.name) to ByteArray(32) { 1 }
    }

    override suspend fun sync(pc: PairedPc, token: String, request: SyncRequest): SyncResponse {
        if (failNext) throw IOException("PC unreachable")
        received += request.changes
        return SyncResponse(Changes(upToSeq = 0))
    }
}

/** Keeps the token as-is: Android Keystore does not exist in unit tests. */
private object PlainCipher : TokenCipher {
    override fun encrypt(text: String) = text.toByteArray()
    override fun decrypt(data: ByteArray) = String(data)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PcSyncCoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val transport = FakeTransport()
    private val library = PhoneLibrary()
    private val paired = PairedPcStore(context.getSharedPreferences("pc-sync-test", Context.MODE_PRIVATE), PlainCipher)
    private var wifi = true
    private val core = PcSyncCore(
        engine = SyncEngine(InMemorySyncStore(), library, HlcClock("phone")),
        transport = transport,
        paired = paired,
        deviceId = "phone-id",
        deviceName = "Galaxy A55",
        onHomeWifi = { wifi },
        rediscover = { null },
    )
    private val pc = FoundPc("PC de Alex", "192.168.1.20", 47321, "pc-id")

    @Test
    fun pairingStoresTokenAndPin() = runBlocking {
        core.pair(pc, "123456").getOrThrow()
        val saved = paired.load()!!
        assertEquals("pc-id", saved.serverId)
        assertEquals("192.168.1.20", saved.host)
        assertEquals("token-123", paired.token())
        assertTrue(saved.certSha256.contentEquals(ByteArray(32) { 1 }))
        assertEquals("123456", transport.pairedWith)
    }

    @Test
    fun offlineOrPcAwayFailsQuietlyAndKeepsChanges() = runBlocking {
        core.pair(pc, "123456").getOrThrow()
        transport.failNext = true
        assertTrue(core.syncNow().isFailure)
        library.songs = listOf("a", "b")
        transport.failNext = false
        assertTrue(core.syncNow().isSuccess)
        // Everything not delivered before reaches the PC on the next successful sync.
        assertEquals(setOf("a", "b"), transport.received.last().entries.map { it.song.id }.toSet())
    }

    @Test
    fun pairingWithAnotherPcSendsTheWholeLibrary() = runBlocking {
        core.pair(pc, "123456").getOrThrow()
        assertTrue(core.syncNow().isSuccess)
        core.pair(pc.copy(serverId = "other-pc-id"), "654321").getOrThrow()
        assertTrue(core.syncNow().isSuccess)
        // The new PC knows nothing yet: what the first one already had must reach it too.
        assertEquals(setOf("LP1"), transport.received.last().playlists.map { it.id }.toSet())
        assertEquals(setOf("a"), transport.received.last().entries.map { it.song.id }.toSet())
    }

    @Test
    fun notOnHomeWifiDoesNotSync() = runBlocking {
        core.pair(pc, "123456").getOrThrow()
        wifi = false
        assertTrue(core.syncNow().isFailure)
        assertTrue(transport.received.isEmpty())
    }

    @Test
    fun notPairedDoesNothing() = runBlocking {
        assertTrue(core.syncNow().isFailure)
        assertNull(paired.load())
    }

    @Test
    fun unpairForgetsThePc() = runBlocking {
        core.pair(pc, "123456").getOrThrow()
        core.unpair()
        assertNull(paired.load())
        assertNull(paired.token())
    }

    @Test
    fun onlyThePinnedCertificateIsAccepted() {
        val certificate = ByteArray(64) { it.toByte() }
        val pinned = PinnedCertificateTrustManager(dev.alextorres.vegas.sync.Pairing.sha256(certificate))
        assertTrue(pinned.matches(certificate))
        assertTrue(!pinned.matches(certificate.copyOf().also { it[0] = 42 }))
    }
}
