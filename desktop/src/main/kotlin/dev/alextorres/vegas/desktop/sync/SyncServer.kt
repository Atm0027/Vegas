/**
 * Vegas desktop: the HTTPS endpoint the phone syncs with. Only answers devices on the private home
 * network, only pairs with the code on screen, and only syncs with a paired phone's token.
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.desktop.sync

import dev.alextorres.vegas.sync.PairRequest
import dev.alextorres.vegas.sync.PairResponse
import dev.alextorres.vegas.sync.Pairing
import dev.alextorres.vegas.sync.SyncEngine
import dev.alextorres.vegas.sync.SyncRequest
import io.ktor.http.HttpStatusCode
import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore

class SyncServerDeps(
    val engine: SyncEngine,
    val pairing: PairingState,
    val devices: PairedDevices,
    val serverId: String,
    val serverName: String,
    val certSha256: ByteArray,
    val remoteHostOf: (ApplicationCall) -> String = { it.request.origin.remoteAddress },
    val onSynced: (deviceId: String) -> Unit = {},
)

fun Application.syncModule(deps: SyncServerDeps) {
    install(ContentNegotiation) { json() }
    install(
        createApplicationPlugin("PrivateNetworkOnly") {
            onCall { call -> if (!PrivateNetwork.isPrivate(deps.remoteHostOf(call))) call.respond(HttpStatusCode.Forbidden) }
        },
    )
    routing {
        post("/pair") {
            val request = call.receive<PairRequest>()
            val ok = runCatching {
                deps.pairing.verify(Pairing.decode(request.proof), Pairing.decode(request.nonce), deps.certSha256)
            }.getOrDefault(false)
            if (!ok) return@post call.respond(HttpStatusCode.Forbidden)
            val token = Pairing.newToken()
            deps.devices.add(request.deviceId, request.deviceName, token)
            call.respond(PairResponse(token, deps.serverId, deps.serverName))
        }
        post("/sync") {
            val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
            val request = call.receive<SyncRequest>()
            if (token == null || !deps.devices.check(request.deviceId, token)) return@post call.respond(HttpStatusCode.Unauthorized)
            val response = deps.engine.respond(request.deviceId, request)
            deps.devices.touch(request.deviceId)
            deps.onSynced(request.deviceId)
            call.respond(response)
        }
    }
}

object PrivateNetwork {
    fun isPrivate(host: String): Boolean {
        if (host == "localhost") return true
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
        if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) return true
        // IPv6 unique local addresses (fc00::/7) are the private range of home routers.
        return address is Inet6Address && (address.address[0].toInt() and 0xFE) == 0xFC
    }
}

/** The self-signed certificate the phone pins when it pairs, kept between runs. */
class SyncCertificate(dataDir: Path) {
    private val storeFile = dataDir.resolve("sync.p12")
    private val passwordFile = dataDir.resolve("sync.key")
    val password: CharArray
    val keyStore: KeyStore

    init {
        Files.createDirectories(dataDir)
        if (!Files.exists(passwordFile) || !Files.exists(storeFile)) {
            password = Pairing.newToken().toCharArray()
            keyStore = buildKeyStore {
                certificate(ALIAS) {
                    this.password = String(this@SyncCertificate.password)
                    daysValid = 3650
                }
            }
            Files.writeString(passwordFile, String(password))
            Files.newOutputStream(storeFile).use { keyStore.store(it, password) }
        } else {
            password = Files.readString(passwordFile).trim().toCharArray()
            keyStore = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(storeFile).use { load(it, password) } }
        }
    }

    val sha256: ByteArray get() = Pairing.sha256(keyStore.getCertificate(ALIAS).encoded)

    companion object {
        const val ALIAS = "vegas-sync"
    }
}

/** Starts HTTPS on the first free port from 47321. */
fun startSyncServer(deps: SyncServerDeps, certificate: SyncCertificate): Pair<EmbeddedServer<NettyApplicationEngine, *>, Int> {
    val port = (DEFAULT_PORT until DEFAULT_PORT + 20).first { p -> runCatching { ServerSocket(p).close() }.isSuccess }
    val server = embeddedServer(
        Netty,
        configure = {
            sslConnector(
                keyStore = certificate.keyStore,
                keyAlias = SyncCertificate.ALIAS,
                keyStorePassword = { certificate.password },
                privateKeyPassword = { certificate.password },
            ) { this.port = port }
        },
    ) { syncModule(deps) }
    server.start(wait = false)
    return server to port
}

/** The address of the interface that reaches the home router (skips VPN and virtual adapters when possible). */
fun homeNetworkAddress(): InetAddress? =
    runCatching {
        java.net.DatagramSocket().use { socket ->
            socket.connect(InetAddress.getByName("192.168.1.1"), 9)
            socket.localAddress.takeIf { it is Inet4Address && !it.isAnyLocalAddress }
        }
    }.getOrNull()

const val DEFAULT_PORT = 47_321
