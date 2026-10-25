/**
 * Vegas: the phone side of the home-network sync with Vegas for PC — pairing with the code shown on
 * the PC, then exchanging changes. Fails quietly when not on home wifi or the PC is off: local changes
 * are already recorded and go out on the next successful sync.
 * Licensed under GPL-3.0
 */

package com.metrolist.music.sync

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import dev.alextorres.vegas.sync.PairResponse
import dev.alextorres.vegas.sync.Pairing
import dev.alextorres.vegas.sync.SyncEngine
import dev.alextorres.vegas.sync.SyncRequest
import dev.alextorres.vegas.sync.SyncResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.X509TrustManager

/** A PC found on the home network. */
data class FoundPc(val name: String, val host: String, val port: Int, val serverId: String)

/** The PC this phone is paired with; the token itself is kept encrypted apart. */
class PairedPc(val serverId: String, val name: String, val host: String, val port: Int, val certSha256: ByteArray)

interface SyncTransport {
    /** Returns the PC's answer and the SHA-256 of the certificate it presented. */
    suspend fun pair(pc: FoundPc, code: String, deviceId: String, deviceName: String): Pair<PairResponse, ByteArray>

    suspend fun sync(pc: PairedPc, token: String, request: SyncRequest): SyncResponse
}

interface TokenCipher {
    fun encrypt(text: String): ByteArray

    fun decrypt(data: ByteArray): String
}

/** AES-GCM with a key that never leaves Android Keystore. */
class KeystoreTokenCipher : TokenCipher {
    private val key: SecretKey by lazy {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    override fun encrypt(text: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
        return cipher.iv + cipher.doFinal(text.toByteArray())
    }

    override fun decrypt(data: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 0, IV_SIZE)) }
        return String(cipher.doFinal(data, IV_SIZE, data.size - IV_SIZE))
    }

    private companion object {
        const val ALIAS = "vegas-pc-sync"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}

class PairedPcStore(private val prefs: SharedPreferences, private val cipher: TokenCipher) {
    fun load(): PairedPc? {
        val id = prefs.getString(SERVER_ID, null) ?: return null
        return PairedPc(
            id,
            prefs.getString(NAME, "PC") ?: "PC",
            prefs.getString(HOST, null) ?: return null,
            prefs.getInt(PORT, 0),
            Pairing.decode(prefs.getString(CERT, null) ?: return null),
        )
    }

    fun token(): String? = prefs.getString(TOKEN, null)?.let { runCatching { cipher.decrypt(Pairing.decode(it)) }.getOrNull() }

    fun save(pc: PairedPc, token: String) =
        prefs.edit {
            putString(SERVER_ID, pc.serverId)
            putString(NAME, pc.name)
            putString(HOST, pc.host)
            putInt(PORT, pc.port)
            putString(CERT, Pairing.encode(pc.certSha256))
            putString(TOKEN, Pairing.encode(cipher.encrypt(token)))
        }

    fun updateAddress(host: String, port: Int) = prefs.edit { putString(HOST, host); putInt(PORT, port) }

    fun clear() = prefs.edit { clear() }

    private companion object {
        const val SERVER_ID = "serverId"
        const val NAME = "name"
        const val HOST = "host"
        const val PORT = "port"
        const val CERT = "certSha256"
        const val TOKEN = "token"
    }
}

/** Accepts exactly the certificate pinned at pairing, whatever name it carries. */
class PinnedCertificateTrustManager(private val pinnedSha256: ByteArray) : X509TrustManager {
    fun matches(encoded: ByteArray) = Pairing.same(Pairing.sha256(encoded), pinnedSha256)

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        if (chain.isEmpty() || !matches(chain[0].encoded)) throw CertificateException("Not the paired PC")
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = throw CertificateException()

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

class PcSyncCore(
    private val engine: SyncEngine,
    private val transport: SyncTransport,
    private val paired: PairedPcStore,
    private val deviceId: String,
    private val deviceName: String,
    private val onHomeWifi: () -> Boolean,
    /** Finds the paired PC again when its address changed (router gave it a new IP). */
    private val rediscover: suspend (serverId: String) -> FoundPc?,
) {
    private val mutex = Mutex()
    private val _pairedPc = MutableStateFlow(paired.load())
    val pairedPc: StateFlow<PairedPc?> = _pairedPc.asStateFlow()
    private val _lastSync = MutableStateFlow<Long?>(null)
    val lastSync: StateFlow<Long?> = _lastSync.asStateFlow()

    suspend fun pair(pc: FoundPc, code: String): Result<PairedPc> =
        runCatchingNonCancel {
            val (response, certSha256) = transport.pair(pc, code, deviceId, deviceName)
            val saved = PairedPc(response.serverId, response.serverName, pc.host, pc.port, certSha256)
            paired.save(saved, response.token)
            _pairedPc.value = saved
            saved
        }

    fun unpair() {
        paired.clear()
        _pairedPc.value = null
    }

    suspend fun syncNow(): Result<Unit> =
        mutex.withLock {
            runCatchingNonCancel {
                var pc = checkNotNull(paired.load()) { "Not paired" }
                val token = checkNotNull(paired.token()) { "No token" }
                check(onHomeWifi()) { "Not on home wifi" }
                val request = SyncRequest(deviceId, engine.prepareOutgoing(PEER), engine.receivedWatermark(PEER))
                val response = try {
                    transport.sync(pc, token, request)
                } catch (e: java.io.IOException) {
                    val found = rediscover(pc.serverId)?.takeIf { it.host != pc.host || it.port != pc.port } ?: throw e
                    paired.updateAddress(found.host, found.port)
                    pc = checkNotNull(paired.load())
                    transport.sync(pc, token, request)
                }
                engine.markSent(PEER, request.changes.upToSeq)
                engine.receive(PEER, response.changes)
                _lastSync.value = System.currentTimeMillis()
            }
        }

    private inline fun <T> runCatchingNonCancel(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    private companion object {
        const val PEER = "pc"
    }
}
