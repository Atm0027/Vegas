/**
 * Vegas desktop: decodes one source at a time with FFmpeg on its own thread and feeds a [PcmSink].
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.desktop.playback

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import java.nio.ShortBuffer
import java.nio.file.Path

sealed interface AudioSource {
    data class Remote(val stream: ResolvedStream) : AudioSource

    data class LocalFile(val path: Path) : AudioSource
}

enum class EngineState { IDLE, LOADING, PLAYING, PAUSED, ENDED, ERROR }

/** What the player needs from an engine; lets the queue logic be tested without decoding audio. */
interface PlaybackEngine {
    val state: StateFlow<EngineState>
    val positionMs: StateFlow<Long>
    val durationMs: StateFlow<Long>
    val errors: SharedFlow<Throwable>
    var volume: Float

    fun load(source: AudioSource, startMs: Long = 0, playWhenReady: Boolean = true)

    fun play()

    fun pause()

    fun seekTo(ms: Long)

    fun release()
}

class AudioEngine(
    private val sinkFactory: () -> PcmSink = ::JavaSoundSink,
) : PlaybackEngine {
    private val _state = MutableStateFlow(EngineState.IDLE)
    private val _position = MutableStateFlow(0L)
    private val _duration = MutableStateFlow(0L)
    private val _errors = MutableSharedFlow<Throwable>(extraBufferCapacity = 8)

    override val state: StateFlow<EngineState> = _state.asStateFlow()
    override val positionMs: StateFlow<Long> = _position.asStateFlow()
    override val durationMs: StateFlow<Long> = _duration.asStateFlow()
    override val errors: SharedFlow<Throwable> = _errors

    private val lock = Object()

    @Volatile private var generation = 0

    @Volatile private var paused = false

    @Volatile private var pendingSeekMs: Long? = null

    @Volatile private var sink: PcmSink? = null

    @Volatile private var worker: Thread? = null

    @Volatile
    override var volume: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            sink?.volume = field
        }

    override fun load(source: AudioSource, startMs: Long, playWhenReady: Boolean) {
        val gen = synchronized(lock) {
            generation++
            paused = !playWhenReady
            pendingSeekMs = null
            lock.notifyAll()
            generation
        }
        _state.value = EngineState.LOADING
        _position.value = startMs
        _duration.value = 0
        worker = Thread({ decode(gen, source, startMs) }, "vegas-decoder-$gen").apply {
            isDaemon = true
            start()
        }
    }

    override fun play() {
        synchronized(lock) {
            paused = false
            lock.notifyAll()
        }
        sink?.resume()
        if (_state.value == EngineState.PAUSED) _state.value = EngineState.PLAYING
    }

    override fun pause() {
        synchronized(lock) { paused = true }
        sink?.pause()
        if (_state.value == EngineState.PLAYING) _state.value = EngineState.PAUSED
    }

    override fun seekTo(ms: Long) {
        synchronized(lock) {
            pendingSeekMs = ms.coerceAtLeast(0)
            lock.notifyAll()
        }
        _position.value = ms.coerceAtLeast(0)
    }

    /** Stops decoding and waits briefly so the source file is no longer held open. */
    override fun release() {
        synchronized(lock) {
            generation++
            lock.notifyAll()
        }
        worker?.takeIf { it !== Thread.currentThread() }?.join(RELEASE_WAIT_MS)
        worker = null
        _state.value = EngineState.IDLE
    }

    private fun isCurrent(gen: Int) = gen == generation

    private fun decode(gen: Int, source: AudioSource, startMs: Long) {
        var grabber: FFmpegFrameGrabber? = null
        var out: PcmSink? = null
        try {
            grabber = grabberFor(source).apply { start() }
            if (!isCurrent(gen)) return
            _duration.value = grabber.lengthInTime / 1000
            if (startMs > 0) grabber.timestamp = startMs * 1000
            var output = openSink().also { out = it }
            if (paused) output.pause()
            _state.value = if (paused) EngineState.PAUSED else EngineState.PLAYING

            var reopened = false
            var lastPublish = 0L
            val bytes = ByteArray(16_384)
            while (isCurrent(gen)) {
                val seek = synchronized(lock) {
                    while (paused && pendingSeekMs == null && isCurrent(gen)) lock.wait()
                    pendingSeekMs.also { pendingSeekMs = null }
                }
                if (!isCurrent(gen)) break
                if (seek != null) {
                    grabber.timestamp = seek * 1000
                    output.flush()
                    _position.value = seek
                    continue
                }
                val frame = grabber.grabSamples() ?: run {
                    if (isCurrent(gen)) _state.value = EngineState.ENDED
                    return
                }
                val samples = frame.samples?.firstOrNull() as? ShortBuffer ?: continue
                val count = samples.remaining()
                val buf = if (bytes.size >= count * 2) bytes else ByteArray(count * 2)
                for (i in 0 until count) {
                    val v = samples.get(samples.position() + i).toInt()
                    buf[i * 2] = (v and 0xff).toByte()
                    buf[i * 2 + 1] = (v shr 8).toByte()
                }
                try {
                    output.write(buf, count * 2)
                } catch (e: Exception) {
                    // The output device went away (headphones unplugged): reopen once on the default device.
                    if (reopened) throw e
                    reopened = true
                    runCatching { output.close() }
                    output = openSink().also { out = it }
                    output.write(buf, count * 2)
                }
                val now = System.currentTimeMillis()
                if (now - lastPublish >= POSITION_INTERVAL_MS) {
                    lastPublish = now
                    _position.value = grabber.timestamp / 1000
                }
            }
        } catch (e: Exception) {
            if (isCurrent(gen)) {
                _state.value = EngineState.ERROR
                _errors.tryEmit(e)
            }
        } finally {
            runCatching { out?.close() }
            if (sink === out) sink = null
            runCatching { grabber?.release() }
        }
    }

    private fun openSink(): PcmSink =
        sinkFactory().apply {
            volume = this@AudioEngine.volume
            open(SAMPLE_RATE, CHANNELS)
        }.also { sink = it }

    private fun grabberFor(source: AudioSource): FFmpegFrameGrabber =
        when (source) {
            is AudioSource.LocalFile -> FFmpegFrameGrabber(source.path.toFile())
            is AudioSource.Remote ->
                FFmpegFrameGrabber(source.stream.url).apply {
                    setOption("headers", source.stream.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" })
                    setOption("reconnect", "1")
                    setOption("reconnect_streamed", "1")
                }
        }.apply {
            sampleFormat = avutil.AV_SAMPLE_FMT_S16
            audioChannels = CHANNELS
            sampleRate = SAMPLE_RATE
        }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        const val POSITION_INTERVAL_MS = 200L
        const val RELEASE_WAIT_MS = 2_000L

        init {
            avutil.av_log_set_level(avutil.AV_LOG_ERROR)
        }
    }
}
