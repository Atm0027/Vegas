/**
 * Vegas desktop: starts the sync server and announces it on the home network (mDNS) so the phone finds it.
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.desktop.sync

import dev.alextorres.vegas.desktop.db.Library
import dev.alextorres.vegas.desktop.db.VegasDatabase
import dev.alextorres.vegas.desktop.settings.DesktopSettings
import dev.alextorres.vegas.sync.HlcClock
import dev.alextorres.vegas.sync.SyncEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

class SyncService(dataDir: Path, db: VegasDatabase, library: Library, settings: DesktopSettings) : AutoCloseable {
    val serverId: String = dataDir.resolve("sync.id").let { f ->
        runCatching { Files.readString(f).trim() }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString().also { Files.createDirectories(dataDir); Files.writeString(f, it) }
    }
    val serverName: String = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("PC")
    val pairing = PairingState()
    val devices = PairedDevices(dataDir.resolve("paired.json"))
    val engine = SyncEngine(DesktopSyncStore(db), DesktopLibraryAdapter(library, settings), HlcClock(serverId))

    private val _lastSync = MutableStateFlow<Long?>(null)
    val lastSync: StateFlow<Long?> = _lastSync.asStateFlow()

    private val certificate = SyncCertificate(dataDir)
    private val server: Any?
    private var jmdns: JmDNS? = null

    init {
        val deps = SyncServerDeps(engine, pairing, devices, serverId, serverName, certificate.sha256, onSynced = { _lastSync.value = System.currentTimeMillis() })
        val started = runCatching { startSyncServer(deps, certificate) }.getOrNull()
        server = started?.first
        started?.second?.let { port -> announce(port) }
    }

    private fun announce(port: Int) {
        Thread({
            runCatching {
                jmdns = JmDNS.create(homeNetworkAddress() ?: InetAddress.getLocalHost(), serverName).apply {
                    registerService(ServiceInfo.create(SERVICE_TYPE, serverName, port, 0, 0, mapOf("id" to serverId)))
                }
            }
        }, "vegas-mdns").apply { isDaemon = true }.start()
    }

    override fun close() {
        runCatching { jmdns?.unregisterAllServices(); jmdns?.close() }
        runCatching { (server as? io.ktor.server.engine.EmbeddedServer<*, *>)?.stop(500, 1_000) }
    }

    companion object {
        const val SERVICE_TYPE = "_vegas-sync._tcp.local."
    }
}
