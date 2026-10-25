/**
 * Vegas desktop: keeps downloading whatever should be offline. Follows the pending query, so a song
 * added to a marked list (here or by sync from the phone) downloads on its own. Three at a time, one
 * while a song streams. A failed song waits 1, 5 and 15 minutes between tries and then counts as
 * failed until «Reintentar»; a lost connection does not count as a try and is retried every minute.
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.desktop.offline

import dev.alextorres.vegas.desktop.db.SongEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DownloadState(
    /** Songs waiting or downloading. */
    val queued: Set<String> = emptySet(),
    /** Progress 0..1 of the songs downloading right now. */
    val progress: Map<String, Float> = emptyMap(),
    val failed: Set<String> = emptySet(),
)

class DownloadManager(
    private val scope: CoroutineScope,
    private val offline: OfflineLibrary,
    private val download: suspend (SongEntity, (Float) -> Unit) -> Unit,
    private val slots: DownloadSlots = DownloadSlots(MAX_PARALLEL),
    private val now: () -> Long = System::currentTimeMillis,
    private val retryDelaysMs: List<Long> = listOf(60_000L, 300_000L, 900_000L),
    private val tickMs: Long = 60_000L,
) {
    private class Attempts(val failures: Int, val nextAtMs: Long)

    private val _state = MutableStateFlow(DownloadState())
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    private val lock = Any()
    private var pending: List<SongEntity> = emptyList()
    private val running = mutableMapOf<String, Job>()
    private val attempts = mutableMapOf<String, Attempts>()
    /** Finished, but the pending query may not have caught up yet. */
    private val finished = mutableSetOf<String>()

    fun start() {
        scope.launch {
            offline.pending().collect { songs ->
                synchronized(lock) {
                    pending = songs
                    finished.retainAll(songs.mapTo(HashSet()) { it.id })
                }
                launchDue()
            }
        }
        scope.launch {
            while (true) {
                delay(tickMs)
                launchDue()
            }
        }
    }

    fun setStreaming(streaming: Boolean) = slots.setStreaming(streaming)

    fun retryFailed() {
        synchronized(lock) { attempts.clear() }
        _state.update { it.copy(failed = emptySet()) }
        launchDue()
    }

    private fun launchDue() {
        synchronized(lock) {
            val wanted = pending.associateBy { it.id }
            // A list was unmarked or the song was removed: stop its download (the .part file stays for later).
            running.keys.filter { it !in wanted }.forEach { running.remove(it)?.cancel() }
            val t = now()
            for (song in pending) {
                if (song.id in running || song.id in finished) continue
                val a = attempts[song.id]
                if (a != null && (a.failures > retryDelaysMs.size || a.nextAtMs > t)) continue
                val job = scope.launch(start = CoroutineStart.LAZY) { run(song) }
                running[song.id] = job
                job.start()
            }
            val failed = attempts.filter { (id, a) -> id in wanted && a.failures > retryDelaysMs.size }.keys
            _state.update { it.copy(queued = wanted.keys - finished, failed = failed) }
        }
    }

    private suspend fun run(song: SongEntity) {
        try {
            slots.withSlot {
                setProgress(song.id, 0f)
                download(song) { p -> setProgress(song.id, p) }
            }
            synchronized(lock) {
                attempts.remove(song.id)
                finished += song.id
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            synchronized(lock) {
                val failures = attempts[song.id]?.failures ?: 0
                attempts[song.id] = if (NetworkFailure.isNetworkFailure(e)) {
                    Attempts(failures, now() + tickMs)
                } else {
                    Attempts(failures + 1, now() + (retryDelaysMs.getOrNull(failures) ?: 0L))
                }
            }
        } finally {
            _state.update { it.copy(progress = it.progress - song.id) }
            val me = currentCoroutineContext()[Job]
            synchronized(lock) { if (running[song.id] === me) running.remove(song.id) }
        }
        launchDue()
    }

    private fun setProgress(songId: String, p: Float) {
        _state.update { it.copy(progress = it.progress + (songId to p)) }
    }

    companion object {
        const val MAX_PARALLEL = 3
    }
}
