/**
 * Vegas desktop: the pairing code currently shown on screen, and the phones already paired.
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.desktop.sync

import dev.alextorres.vegas.sync.Pairing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** A 6-digit code valid for two minutes and three attempts. */
class PairingState(private val now: () -> Long = System::currentTimeMillis) {
    data class Active(val code: String, val expiresAtMs: Long)

    private val _active = MutableStateFlow<Active?>(null)
    val active: StateFlow<Active?> = _active.asStateFlow()
    private var attempts = 0

    @Synchronized
    fun start(): String {
        attempts = 0
        return Pairing.newCode().also { _active.value = Active(it, now() + VALID_MS) }
    }

    @Synchronized
    fun cancel() {
        _active.value = null
    }

    fun isActive(): Boolean = _active.value?.let { now() < it.expiresAtMs } == true

    /** True once for the right proof; three wrong proofs or the expiry end the code. */
    @Synchronized
    fun verify(proof: ByteArray, nonce: ByteArray, certSha256: ByteArray): Boolean {
        val current = _active.value ?: return false
        if (now() >= current.expiresAtMs) {
            _active.value = null
            return false
        }
        if (Pairing.same(proof, Pairing.proof(current.code, certSha256, nonce))) {
            _active.value = null
            return true
        }
        if (++attempts >= MAX_ATTEMPTS) _active.value = null
        return false
    }

    private companion object {
        const val VALID_MS = 2 * 60_000L
        const val MAX_ATTEMPTS = 3
    }
}

@Serializable
data class PairedDevice(val deviceId: String, val name: String, val tokenSha256: String, val pairedAtMs: Long, val lastSyncMs: Long? = null)

/** Paired phones in %APPDATA%\Vegas\paired.json; only a hash of each token is kept. */
class PairedDevices(private val file: Path, private val now: () -> Long = System::currentTimeMillis) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(PairedDevice.serializer())
    private val _devices = MutableStateFlow(load())
    val devices: StateFlow<List<PairedDevice>> = _devices.asStateFlow()

    @Synchronized
    fun add(deviceId: String, name: String, token: String) =
        save(_devices.value.filterNot { it.deviceId == deviceId } + PairedDevice(deviceId, name, hash(token), now()))

    @Synchronized
    fun remove(deviceId: String) = save(_devices.value.filterNot { it.deviceId == deviceId })

    fun check(deviceId: String, token: String): Boolean {
        val device = _devices.value.firstOrNull { it.deviceId == deviceId } ?: return false
        return Pairing.same(Pairing.decode(device.tokenSha256), Pairing.sha256(token.toByteArray()))
    }

    @Synchronized
    fun touch(deviceId: String) = save(_devices.value.map { if (it.deviceId == deviceId) it.copy(lastSyncMs = now()) else it })

    private fun hash(token: String) = Pairing.encode(Pairing.sha256(token.toByteArray()))

    private fun load(): List<PairedDevice> = runCatching { json.decodeFromString(serializer, Files.readString(file)) }.getOrDefault(emptyList())

    private fun save(list: List<PairedDevice>) {
        _devices.value = list
        runCatching {
            Files.createDirectories(file.toAbsolutePath().parent)
            val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.writeString(tmp, json.encodeToString(serializer, list))
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }
}
