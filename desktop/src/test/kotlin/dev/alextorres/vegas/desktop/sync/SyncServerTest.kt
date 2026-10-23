package dev.alextorres.vegas.desktop.sync

import dev.alextorres.vegas.sync.Changes
import dev.alextorres.vegas.sync.HlcClock
import dev.alextorres.vegas.sync.InMemorySyncStore
import dev.alextorres.vegas.sync.LibraryAdapter
import dev.alextorres.vegas.sync.LibrarySnapshot
import dev.alextorres.vegas.sync.LocalEntry
import dev.alextorres.vegas.sync.LocalPlaylist
import dev.alextorres.vegas.sync.PairRequest
import dev.alextorres.vegas.sync.PairResponse
import dev.alextorres.vegas.sync.Pairing
import dev.alextorres.vegas.sync.SongMeta
import dev.alextorres.vegas.sync.SyncEngine
import dev.alextorres.vegas.sync.SyncRequest
import dev.alextorres.vegas.sync.SyncResponse
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SyncServerTest {
    private val cert = ByteArray(32) { 3 }
    private var now = 0L
    private val pairing = PairingState(now = { now })
    private val devices = PairedDevices(Files.createTempFile("paired", ".json").also { Files.delete(it) })

    private val pcLibrary = object : LibraryAdapter {
        override suspend fun snapshot() = LibrarySnapshot(
            listOf(LocalPlaylist("pc-list", "Prueba PC", false, 0, listOf(LocalEntry("row", SongMeta("z", "Z", "Artist"))))),
            emptyMap(),
        )
        override suspend fun apply(changes: dev.alextorres.vegas.sync.AppliedChanges) = emptyMap<String, String>()
    }
    private val syncEngine = SyncEngine(InMemorySyncStore(), pcLibrary, HlcClock("pc"))

    private fun ApplicationTestBuilder.setUp(remoteHost: String = "192.168.1.20") {
        application {
            syncModule(SyncServerDeps(syncEngine, pairing, devices, serverId = "pc-id", serverName = "PC de Alex", certSha256 = cert, remoteHostOf = { remoteHost }))
        }
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient { install(ContentNegotiation) { json() } }

    private fun pairRequest(code: String): PairRequest {
        val nonce = Pairing.newNonce()
        return PairRequest("phone-id", "Galaxy A55", Pairing.encode(nonce), Pairing.encode(Pairing.proof(code, cert, nonce)))
    }

    @Test
    fun pairWithRightCodeReturnsToken() = testApplication {
        setUp()
        val code = pairing.start()
        val response = jsonClient().post("/pair") { contentType(ContentType.Application.Json); setBody(pairRequest(code)) }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<PairResponse>()
        assertEquals("pc-id", body.serverId)
        assertTrue(devices.check("phone-id", body.token))
        assertFalse(pairing.isActive())
    }

    @Test
    fun wrongCodeThreeTimesInvalidatesCode() = testApplication {
        setUp()
        val code = pairing.start()
        val client = jsonClient()
        repeat(3) {
            val r = client.post("/pair") { contentType(ContentType.Application.Json); setBody(pairRequest("000000")) }
            assertEquals(HttpStatusCode.Forbidden, r.status)
        }
        val late = client.post("/pair") { contentType(ContentType.Application.Json); setBody(pairRequest(code)) }
        assertEquals(HttpStatusCode.Forbidden, late.status)
    }

    @Test
    fun expiredCodeRejected() = testApplication {
        setUp()
        val code = pairing.start()
        now += 2 * 60_000 + 1
        val r = jsonClient().post("/pair") { contentType(ContentType.Application.Json); setBody(pairRequest(code)) }
        assertEquals(HttpStatusCode.Forbidden, r.status)
    }

    @Test
    fun syncWithoutTokenIs401() = testApplication {
        setUp()
        val r = jsonClient().post("/sync") { contentType(ContentType.Application.Json); setBody(SyncRequest("phone-id", Changes(), 0)) }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    @Test
    fun syncWithTokenExchangesChanges() = testApplication {
        setUp()
        val client = jsonClient()
        val token = client.post("/pair") { contentType(ContentType.Application.Json); setBody(pairRequest(pairing.start())) }.body<PairResponse>().token
        val r = client.post("/sync") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(SyncRequest("phone-id", Changes(), 0))
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(listOf("Prueba PC"), r.body<SyncResponse>().changes.playlists.map { it.name })
    }

    @Test
    fun tokenOfAnotherDeviceIsRejected() = testApplication {
        setUp()
        val client = jsonClient()
        val token = client.post("/pair") { contentType(ContentType.Application.Json); setBody(pairRequest(pairing.start())) }.body<PairResponse>().token
        val r = client.post("/sync") { contentType(ContentType.Application.Json); bearerAuth(token); setBody(SyncRequest("someone-else", Changes(), 0)) }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    @Test
    fun publicAddressRejected() = testApplication {
        setUp(remoteHost = "8.8.8.8")
        val r = jsonClient().post("/pair") { contentType(ContentType.Application.Json); setBody(pairRequest(pairing.start())) }
        assertEquals(HttpStatusCode.Forbidden, r.status)
    }

    @Test
    fun privateNetworkRules() {
        listOf("192.168.1.5", "10.0.0.2", "172.16.4.1", "172.31.255.1", "169.254.3.3", "127.0.0.1", "::1", "fe80::1", "localhost").forEach {
            assertTrue(it, PrivateNetwork.isPrivate(it))
        }
        listOf("8.8.8.8", "172.32.0.1", "100.64.1.1", "2001:4860::8888").forEach { assertFalse(it, PrivateNetwork.isPrivate(it)) }
    }
}
