/**
 * Vegas: "update metadata of all songs" (Settings > Storage). Asks YouTube Music again for the title, artists,
 * album, artwork and duration of every saved song, in batches, without touching likes, library, downloads or lists.
 * Licensed under GPL-3.0
 */

package com.metrolist.music.utils

import com.metrolist.innertube.YouTube
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.music.di.ApplicationScope
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.models.toMediaMetadata
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

object SongMetadataRefresh {
    const val BATCH_SIZE = 50
    const val MAX_FAILED_BATCHES_IN_A_ROW = 3

    /** Fresh metadata over the existing row; a field YouTube left empty keeps its old value. */
    fun merge(
        old: SongEntity,
        meta: MediaMetadata,
    ): SongEntity =
        old.copy(
            title = meta.title.ifBlank { old.title },
            duration = meta.duration.takeIf { it > 0 } ?: old.duration,
            thumbnailUrl = meta.thumbnailUrl?.takeIf { it.isNotBlank() } ?: old.thumbnailUrl,
            albumId = meta.album?.id ?: old.albumId,
            albumName = meta.album?.title ?: old.albumName,
            // The queue endpoint does not always carry the badge, so it only ever adds the mark.
            explicit = old.explicit || meta.explicit,
            libraryAddToken = meta.libraryAddToken ?: old.libraryAddToken,
            libraryRemoveToken = meta.libraryRemoveToken ?: old.libraryRemoveToken,
        )

    data class Outcome(
        val updated: Int,
        val notFound: Int,
        val stoppedEarly: Boolean,
    )

    /**
     * Walks [ids] in batches: [fetch] returns the songs YouTube found (null when the request failed),
     * [apply] saves one of them. Stops after [MAX_FAILED_BATCHES_IN_A_ROW] failed requests in a row.
     */
    suspend fun run(
        ids: List<String>,
        fetch: suspend (List<String>) -> List<MediaMetadata>?,
        apply: (MediaMetadata) -> Boolean,
        onProgress: (done: Int) -> Unit = {},
        pause: suspend () -> Unit = {},
    ): Outcome {
        var updated = 0
        var failedInARow = 0
        var done = 0
        for (batch in ids.chunked(BATCH_SIZE)) {
            val found = fetch(batch)
            if (found == null) {
                if (++failedInARow >= MAX_FAILED_BATCHES_IN_A_ROW) return Outcome(updated, ids.size - updated, stoppedEarly = true)
            } else {
                failedInARow = 0
                val wanted = batch.toSet()
                found.filter { it.id in wanted }.distinctBy { it.id }.forEach { if (apply(it)) updated++ }
            }
            done += batch.size
            onProgress(done)
            pause()
        }
        return Outcome(updated, ids.size - updated, stoppedEarly = false)
    }
}

@Singleton
class MetadataRefresher @Inject constructor(
    private val database: MusicDatabase,
    @ApplicationScope private val scope: CoroutineScope,
) {
    sealed interface State {
        data object Idle : State

        data class Running(val done: Int, val total: Int) : State

        data class Finished(val outcome: SongMetadataRefresh.Outcome) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null

    /** Starts the refresh, or cancels it when one is already running. */
    @Synchronized
    fun toggle() {
        if (job?.isActive == true) {
            job?.cancel()
            _state.value = State.Idle
            return
        }
        job = scope.launch(Dispatchers.IO) {
            val ids = database.refreshableSongIds()
            _state.value = State.Running(0, ids.size)
            val outcome =
                SongMetadataRefresh.run(
                    ids = ids,
                    fetch = { batch ->
                        YouTube.queue(batch)
                            .onFailure { Timber.w(it, "Metadata refresh batch failed") }
                            .getOrNull()
                            ?.map { it.toMediaMetadata() }
                    },
                    apply = { meta ->
                        database.getSongByIdBlocking(meta.id)?.let { song ->
                            database.refreshMetadata(song, meta)
                            true
                        } ?: false
                    },
                    onProgress = { done -> _state.value = State.Running(done, ids.size) },
                    pause = { delay(300) },
                )
            _state.value = State.Finished(outcome)
        }.also { started ->
            // A progress update can land after cancel(); settle back to Idle once the job is really gone.
            started.invokeOnCompletion { cause -> if (cause != null) _state.value = State.Idle }
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface MetadataRefresherEntryPoint {
    fun metadataRefresher(): MetadataRefresher
}
