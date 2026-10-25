/**
 * Vegas: wires the sync with Vegas for PC into the app — HTTPS with the pinned certificate, finding the
 * PC on the home network (NSD), when to sync (app in front, after editing a list, every 15 min on wifi).
 * Licensed under GPL-3.0
 */

package com.metrolist.music.sync

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.room.InvalidationTracker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.utils.dataStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.alextorres.vegas.sync.HlcClock
import dev.alextorres.vegas.sync.PairRequest
import dev.alextorres.vegas.sync.PairResponse
import dev.alextorres.vegas.sync.Pairing
import dev.alextorres.vegas.sync.SyncEngine
import dev.alextorres.vegas.sync.SyncRequest
import dev.alextorres.vegas.sync.SyncResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

@Singleton
class PcSync @Inject constructor(
    @ApplicationContext private val context: Context,
    database: MusicDatabase,
) {
    private val prefs = context.getSharedPreferences("pc_sync", Context.MODE_PRIVATE)
    @Volatile private var lastSoonAt = 0L

    private val deviceId = prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("deviceId", it).apply() }

    val core = PcSyncCore(
        engine = SyncEngine(AndroidSyncStore(database), AndroidLibraryAdapter(database, context.dataStore), HlcClock(deviceId)),
        transport = OkHttpSyncTransport(),
        paired = PairedPcStore(context.getSharedPreferences("pc_sync_pairing", Context.MODE_PRIVATE), KeystoreTokenCipher()),
        deviceId = deviceId,
        deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        onHomeWifi = ::onHomeWifi,
        rediscover = { id -> withTimeoutOrNull(6_000) { discover().first { list -> list.any { it.serverId == id } }.first { it.serverId == id } } },
    )

    init {
        // A list edited on the phone goes to the PC a few seconds later.
        database.invalidationTracker.addObserver(
            object : InvalidationTracker.Observer(arrayOf("playlist", "playlist_song_map")) {
                override fun onInvalidated(tables: Set<String>) {
                    if (core.pairedPc.value == null) return
                    // Bursts of writes (marking downloads, the editor) must not flood WorkManager with binder calls.
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastSoonAt < SOON_MIN_INTERVAL_MS) return
                    lastSoonAt = now
                    SyncWorker.soon(context)
                }
            },
        )
        if (core.pairedPc.value != null) SyncWorker.schedulePeriodic(context)
    }

    suspend fun pair(pc: FoundPc, code: String) =
        core.pair(pc, code).onSuccess {
            SyncWorker.schedulePeriodic(context)
            core.syncNow()
        }

    fun unpair() {
        core.unpair()
        SyncWorker.cancel(context)
    }

    /** PCs announcing Vegas on the home network, updated as they appear and disappear. */
    fun discover(): Flow<List<FoundPc>> =
        callbackFlow {
            val nsd = context.getSystemService(NsdManager::class.java)
            val found = linkedMapOf<String, FoundPc>()
            val listener = object : NsdManager.DiscoveryListener {
                override fun onServiceFound(info: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            val id = resolved.attributes["id"]?.let(::String) ?: return
                            @Suppress("DEPRECATION")
                            val host = resolved.host?.hostAddress ?: return
                            found[resolved.serviceName] = FoundPc(resolved.serviceName, host, resolved.port, id)
                            trySend(found.values.toList())
                        }

                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                    })
                }

                override fun onServiceLost(info: NsdServiceInfo) {
                    found.remove(info.serviceName)
                    trySend(found.values.toList())
                }

                override fun onDiscoveryStarted(serviceType: String) = Unit

                override fun onDiscoveryStopped(serviceType: String) = Unit

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            }
            trySend(emptyList())
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            awaitClose { runCatching { nsd.stopServiceDiscovery(listener) } }
        }

    private fun onHomeWifi(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private companion object {
        const val SERVICE_TYPE = "_vegas-sync._tcp."
        const val SOON_MIN_INTERVAL_MS = 5_000L
    }
}

/** HTTPS to the PC. Pairing accepts the presented certificate once and binds it into the proof. */
class OkHttpSyncTransport : SyncTransport {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()

    override suspend fun pair(pc: FoundPc, code: String, deviceId: String, deviceName: String): Pair<PairResponse, ByteArray> =
        withContext(Dispatchers.IO) {
            val certSha = fetchCertificateSha256(pc)
            val nonce = Pairing.newNonce()
            val body = PairRequest(deviceId, deviceName, Pairing.encode(nonce), Pairing.encode(Pairing.proof(code, certSha, nonce)))
            val client = client(PinnedCertificateTrustManager(certSha))
            val response = client.newCall(post(pc.host, pc.port, "/pair", json.encodeToString(PairRequest.serializer(), body))).execute()
            response.use {
                if (it.code == 403) throw WrongCodeException()
                if (!it.isSuccessful) throw IOException("Pair failed: ${it.code}")
                json.decodeFromString(PairResponse.serializer(), it.body!!.string()) to certSha
            }
        }

    override suspend fun sync(pc: PairedPc, token: String, request: SyncRequest): SyncResponse =
        withContext(Dispatchers.IO) {
            val call = post(pc.host, pc.port, "/sync", json.encodeToString(SyncRequest.serializer(), request))
                .newBuilder().header("Authorization", "Bearer $token").build()
            client(PinnedCertificateTrustManager(pc.certSha256)).newCall(call).execute().use {
                if (it.code == 401) throw NotPairedException()
                if (!it.isSuccessful) throw IOException("Sync failed: ${it.code}")
                json.decodeFromString(SyncResponse.serializer(), it.body!!.string())
            }
        }

    /** Reads the certificate the PC presents; nothing is sent over this connection. */
    private fun fetchCertificateSha256(pc: FoundPc): ByteArray {
        var seen: ByteArray? = null
        val capture = object : X509TrustManager {
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                seen = Pairing.sha256(chain[0].encoded)
            }

            @SuppressLint("TrustAllX509TrustManager")
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        runCatching { client(capture).newCall(Request.Builder().url("https://${pc.host}:${pc.port}/").head().build()).execute().close() }
        return seen ?: throw IOException("PC not reachable")
    }

    private fun client(trust: X509TrustManager): OkHttpClient {
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trust)
            // The certificate is pinned by its hash; the PC's name or IP is not in it.
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private fun post(host: String, port: Int, path: String, body: String) =
        Request.Builder().url("https://$host:$port$path").post(body.toRequestBody(jsonType)).build()
}

class WrongCodeException : Exception("Wrong or expired code")

class NotPairedException : Exception("The PC no longer knows this phone")

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PcSyncEntryPoint {
    fun pcSync(): PcSync
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val sync = EntryPointAccessors.fromApplication(applicationContext, PcSyncEntryPoint::class.java).pcSync()
        sync.core.syncNow()
        // Failures (PC off, away from home) are normal: just wait for the next run.
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "pc-sync-periodic"
        private const val SOON = "pc-sync-soon"
        private val wifiOnly = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()

        fun schedulePeriodic(context: Context) =
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(wifiOnly).build(),
            )

        fun soon(context: Context) =
            WorkManager.getInstance(context).enqueueUniqueWork(
                SOON,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SyncWorker>().setInitialDelay(5, TimeUnit.SECONDS).setConstraints(wifiOnly).build(),
            )

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
            WorkManager.getInstance(context).cancelUniqueWork(SOON)
        }
    }
}
