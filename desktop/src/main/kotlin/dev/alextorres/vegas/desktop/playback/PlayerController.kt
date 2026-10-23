/**
 * Vegas desktop: the play queue and everything around it — shuffle, repeat, radio continuation when
 * the queue runs out, skipping songs that cannot play and recovering from a dropped connection.
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.desktop.playback

import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
data class Track(
    val id: String,
    val title: String,
    val artists: String,
    val album: String?,
    val thumbnailUrl: String?,
    val durationMs: Long?,
)

fun SongItem.toTrack(): Track =
    Track(
        id = id,
        title = title,
        artists = artists.joinToString(", ") { it.name },
        album = album?.name,
        thumbnailUrl = thumbnail,
        durationMs = duration?.let { it * 1000L },
    )

enum class RepeatMode { OFF, ALL, ONE }

data class PlayerUiState(
    val queue: List<Track> = emptyList(),
    val index: Int = -1,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val message: String? = null,
) {
    val current: Track? get() = queue.getOrNull(index)
}

class PlayerController(
    private val scope: CoroutineScope,
    private val resolver: StreamResolver,
    private val engine: PlaybackEngine,
    private val related: suspend (Track) -> List<Track>,
    private val store: QueueStore,
) {
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    /** The queue in its unshuffled order, kept while shuffle is on. */
    private var original: List<Track>? = null
    private var currentStream: ResolvedStream? = null
    private var retried = false
    private var loadJob: Job? = null

    init {
        store.load()?.let { saved ->
            original = saved.original
            _state.value = PlayerUiState(saved.queue, saved.index, false, saved.positionMs, 0, saved.shuffle, saved.repeat)
        }
        scope.launch {
            engine.state.collect { s ->
                _state.update { it.copy(isPlaying = s == EngineState.PLAYING || s == EngineState.LOADING) }
                if (s == EngineState.ENDED) onEnded()
            }
        }
        scope.launch {
            engine.positionMs.collect { p -> if (currentStream != null) _state.update { it.copy(positionMs = p) } }
        }
        scope.launch { engine.durationMs.collect { d -> _state.update { it.copy(durationMs = knownDuration(d)) } } }
        scope.launch { engine.errors.collect { onError() } }
    }

    fun playQueue(tracks: List<Track>, startIndex: Int, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        val start = startIndex.coerceIn(tracks.indices)
        if (shuffle) {
            original = tracks
            val rest = tracks.filterIndexed { i, _ -> i != start }.shuffled()
            _state.update { it.copy(queue = listOf(tracks[start]) + rest, index = 0, shuffle = true, message = null) }
        } else {
            original = null
            _state.update { it.copy(queue = tracks, index = start, shuffle = false, message = null) }
        }
        loadCurrent(0)
    }

    fun playNext(track: Track) {
        val s = _state.value
        if (s.queue.isEmpty()) return playQueue(listOf(track), 0)
        original = original?.let { it + track }
        _state.update { it.copy(queue = it.queue.toMutableList().apply { add(s.index + 1, track) }) }
        save()
    }

    fun addToQueue(track: Track) {
        if (_state.value.queue.isEmpty()) return playQueue(listOf(track), 0)
        original = original?.let { it + track }
        _state.update { it.copy(queue = it.queue + track) }
        save()
    }

    fun playAt(index: Int) {
        if (index in _state.value.queue.indices) moveTo(index)
    }

    fun next() {
        val s = _state.value
        when {
            s.index + 1 < s.queue.size -> moveTo(s.index + 1)
            s.repeat == RepeatMode.ALL && s.queue.isNotEmpty() -> moveTo(0)
            else -> continueWithRelated()
        }
    }

    fun previous() {
        val s = _state.value
        if (engine.positionMs.value > RESTART_THRESHOLD_MS || s.index <= 0) {
            engine.seekTo(0)
        } else {
            moveTo(s.index - 1)
        }
    }

    fun togglePlay() {
        if (engine.state.value == EngineState.PLAYING || engine.state.value == EngineState.LOADING) {
            engine.pause()
            save()
        } else {
            resume()
        }
    }

    fun seekTo(ms: Long) {
        if (currentStream == null) {
            _state.update { it.copy(positionMs = ms) }
        } else {
            engine.seekTo(ms)
        }
    }

    fun setShuffle(on: Boolean) {
        val s = _state.value
        val current = s.current ?: return _state.update { it.copy(shuffle = on) }
        if (on == s.shuffle) return
        if (on) {
            original = s.queue
            val rest = s.queue.filterIndexed { i, _ -> i != s.index }.shuffled()
            _state.update { it.copy(queue = listOf(current) + rest, index = 0, shuffle = true) }
        } else {
            val restored = original ?: s.queue
            original = null
            _state.update { it.copy(queue = restored, index = restored.indexOf(current).coerceAtLeast(0), shuffle = false) }
        }
        save()
    }

    fun cycleRepeat() {
        _state.update { it.copy(repeat = RepeatMode.entries[(it.repeat.ordinal + 1) % RepeatMode.entries.size]) }
        save()
    }

    fun removeFromQueue(index: Int) {
        val s = _state.value
        if (index !in s.queue.indices || index == s.index) return
        val removed = s.queue[index]
        original = original?.let { list -> list.toMutableList().apply { remove(removed) } }
        _state.update { it.copy(queue = it.queue.filterIndexed { i, _ -> i != index }, index = if (index < it.index) it.index - 1 else it.index) }
        save()
    }

    fun moveInQueue(from: Int, to: Int) {
        val s = _state.value
        if (from !in s.queue.indices || to !in s.queue.indices || from == to) return
        val list = s.queue.toMutableList().apply { add(to, removeAt(from)) }
        val index = when (s.index) {
            from -> to
            in (minOf(from, to)..maxOf(from, to)) -> if (from < to) s.index - 1 else s.index + 1
            else -> s.index
        }
        _state.update { it.copy(queue = list, index = index) }
        save()
    }

    /** Shows a short message in the window (the same place playback problems appear). */
    fun notify(message: String) {
        _state.update { it.copy(message = message) }
    }

    /** Saves the queue and the current position; called when the window closes. */
    fun persist() = save()

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    private fun moveTo(index: Int) {
        _state.update { it.copy(index = index) }
        loadCurrent(0)
    }

    private fun loadCurrent(startMs: Long, play: Boolean = true, skipped: Int = 0) {
        val track = _state.value.current ?: return
        retried = false
        currentStream = null
        loadJob?.cancel()
        loadJob = scope.launch {
            resolver.resolve(track.id)
                .onSuccess { stream ->
                    currentStream = stream
                    engine.load(AudioSource.Remote(stream), startMs, play)
                    _state.update { it.copy(durationMs = knownDuration(engine.durationMs.value)) }
                    save()
                }.onFailure {
                    val s = _state.value
                    _state.update { it.copy(message = "No se puede reproducir «${track.title}»") }
                    if (s.index + 1 < s.queue.size && skipped < s.queue.size) {
                        _state.update { it.copy(index = s.index + 1) }
                        loadCurrent(0, play, skipped + 1)
                    }
                }
        }
    }

    private fun resume() {
        val track = _state.value.current ?: return
        val idle = engine.state.value.let { it == EngineState.IDLE || it == EngineState.ENDED || it == EngineState.ERROR }
        val position = if (currentStream == null) _state.value.positionMs else engine.positionMs.value
        retried = false
        scope.launch {
            resolver.resolve(track.id)
                .onSuccess { stream ->
                    // A paused URL may have expired; a new one means reloading at the same spot.
                    if (idle || stream !== currentStream) {
                        currentStream = stream
                        engine.load(AudioSource.Remote(stream), position, true)
                        _state.update { it.copy(durationMs = knownDuration(engine.durationMs.value)) }
                    } else {
                        engine.play()
                    }
                }.onFailure { _state.update { it.copy(message = "Sin conexión") } }
        }
    }

    private fun onEnded() {
        val s = _state.value
        when {
            s.repeat == RepeatMode.ONE -> loadCurrent(0)
            else -> next()
        }
    }

    private fun continueWithRelated() {
        val current = _state.value.current ?: return
        scope.launch {
            val known = _state.value.queue.mapTo(HashSet()) { it.id }
            val more = runCatching { related(current) }.getOrDefault(emptyList()).filter { known.add(it.id) }
            if (more.isEmpty()) return@launch
            original = original?.let { it + more }
            _state.update { it.copy(queue = it.queue + more) }
            moveTo(_state.value.index + 1)
        }
    }

    private fun onError() {
        val track = _state.value.current ?: return
        if (retried) {
            engine.pause()
            _state.update { it.copy(isPlaying = false, message = "Sin conexión") }
            save()
            return
        }
        retried = true
        val position = engine.positionMs.value
        resolver.invalidate(track.id)
        scope.launch {
            resolver.resolve(track.id)
                .onSuccess { stream ->
                    currentStream = stream
                    engine.load(AudioSource.Remote(stream), position, true)
                }.onFailure {
                    engine.pause()
                    _state.update { it.copy(isPlaying = false, message = "Sin conexión") }
                }
        }
    }

    private fun knownDuration(decoded: Long): Long = decoded.takeIf { it > 0 } ?: currentStream?.durationMs ?: 0

    private fun save() {
        val s = _state.value
        if (s.queue.isEmpty()) return
        val position = if (currentStream == null) s.positionMs else engine.positionMs.value
        store.save(SavedQueue(s.queue, original, s.index, position, s.shuffle, s.repeat))
    }

    private companion object {
        const val RESTART_THRESHOLD_MS = 3_000L
    }
}
