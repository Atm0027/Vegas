package dev.alextorres.vegas.desktop.offline

import dev.alextorres.vegas.desktop.db.DownloadEntity
import dev.alextorres.vegas.desktop.db.Library
import dev.alextorres.vegas.desktop.db.SongEntity
import dev.alextorres.vegas.desktop.db.openInMemoryDatabase
import dev.alextorres.vegas.desktop.playback.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class DownloadManagerTest {
    private val db = openInMemoryDatabase()
    private val library = Library(db)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dir = Files.createTempDirectory("vegas-dm")
    private val offline = OfflineLibrary(db, scope, folder = { dir }, remove = { Files.deleteIfExists(it) })
    private var now = 0L

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        dir.toFile().deleteRecursively()
    }

    private fun track(id: String) = Track(id, "Song $id", "Artist", null, null, 180_000)

    private suspend fun finish(song: SongEntity) {
        val file = dir.resolve("${song.id}.m4a")
        Files.write(file, ByteArray(1))
        offline.record(DownloadEntity(song.id, file.toString(), 1, "audio/mp4", 0))
    }

    private suspend fun waitUntil(condition: () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(10) }

    @Test
    fun downloadsEverythingPendingAtMostThreeAtATime() = runBlocking {
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val done = Collections.synchronizedList(mutableListOf<String>())
        val manager = DownloadManager(scope, offline, download = { song, _ ->
            peak.accumulateAndGet(running.incrementAndGet(), ::maxOf)
            delay(50)
            running.decrementAndGet()
            finish(song)
            done += song.id
        }, now = { now })
        val p = library.createPlaylist("Torre")
        library.addTracks(p, (1..7).map { track("song$it") })
        offline.markPlaylist(p)
        manager.start()
        waitUntil { done.size == 7 }
        assertEquals(3, peak.get())
        waitUntil { manager.state.value.queued.isEmpty() }
    }

    @Test
    fun onlyOneAtATimeWhileStreaming() = runBlocking {
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val done = AtomicInteger()
        val manager = DownloadManager(scope, offline, download = { song, _ ->
            peak.accumulateAndGet(running.incrementAndGet(), ::maxOf)
            delay(30)
            running.decrementAndGet()
            finish(song)
            done.incrementAndGet()
        }, now = { now })
        manager.setStreaming(true)
        offline.request((1..4).map { track("song$it") })
        manager.start()
        waitUntil { done.get() == 4 }
        assertEquals(1, peak.get())
    }

    @Test
    fun failedSongWaitsAndIsRetried() = runBlocking {
        val tries = AtomicInteger()
        val manager = DownloadManager(scope, offline, download = { song, _ ->
            if (tries.incrementAndGet() == 1) error("broken stream")
            finish(song)
        }, now = { now }, tickMs = 20)
        offline.request(listOf(track("aaaaaaaaaaa")))
        manager.start()
        waitUntil { tries.get() == 1 }
        delay(100)
        assertEquals(1, tries.get())
        now += 60_000
        waitUntil { manager.state.value.queued.isEmpty() }
        assertEquals(2, tries.get())
    }

    @Test
    fun songThatKeepsFailingIsReportedAndRetriedOnRequest() = runBlocking {
        val tries = AtomicInteger()
        val manager = DownloadManager(scope, offline, download = { _, _ ->
            tries.incrementAndGet()
            error("unavailable")
        }, now = { now }, retryDelaysMs = listOf(0L), tickMs = 20)
        offline.request(listOf(track("aaaaaaaaaaa")))
        manager.start()
        waitUntil { manager.state.value.failed == setOf("aaaaaaaaaaa") }
        val before = tries.get()
        delay(100)
        assertEquals(before, tries.get())
        manager.retryFailed()
        waitUntil { tries.get() > before }
    }

    @Test
    fun lostConnectionDoesNotCountAsAFailure() = runBlocking {
        val tries = AtomicInteger()
        val manager = DownloadManager(scope, offline, download = { _, _ ->
            tries.incrementAndGet()
            throw UnknownHostException("rr1.googlevideo.com")
        }, now = { now }, retryDelaysMs = listOf(0L), tickMs = 20)
        offline.request(listOf(track("aaaaaaaaaaa")))
        manager.start()
        waitUntil { tries.get() == 1 }
        repeat(3) {
            val before = tries.get()
            delay(50) // let the failure be recorded at the current time
            now += 20
            waitUntil { tries.get() > before }
        }
        assertTrue(manager.state.value.failed.isEmpty())
    }

    @Test
    fun unmarkingAListStopsItsDownloads() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val manager = DownloadManager(scope, offline, download = { _, _ ->
            started.complete(Unit)
            delay(60_000)
        }, now = { now })
        val p = library.createPlaylist("Rock")
        library.addTracks(p, listOf(track("aaaaaaaaaaa")))
        offline.markPlaylist(p)
        manager.start()
        started.await()
        offline.unmarkPlaylist(p, deleteFiles = false)
        waitUntil { manager.state.value.progress.isEmpty() && manager.state.value.queued.isEmpty() }
    }
}
