package dev.alextorres.vegas.desktop.playback

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.sin

class AudioEngineTest {
    /** Records what it is given and plays ten times faster than real time. */
    private class FakeSink(private val failFirstWrite: Boolean = false) : PcmSink {
        @Volatile var bytes = 0L
        @Volatile var lastWriteAt = 0L
        private var failed = false
        override var volume = 1f
        override fun open(sampleRate: Int, channels: Int) = Unit
        override fun write(buf: ByteArray, len: Int) {
            if (failFirstWrite && !failed) {
                failed = true
                throw IllegalStateException("device unplugged")
            }
            bytes += len
            lastWriteAt = System.currentTimeMillis()
            Thread.sleep(len * 1000L / BYTES_PER_SECOND / 10)
        }
        override fun flush() = Unit
        override fun close() = Unit
    }

    private lateinit var wav: Path
    private val sinks = mutableListOf<FakeSink>()
    private lateinit var engine: AudioEngine

    @Before
    fun setUp() {
        wav = Files.createTempFile("tone", ".wav")
        val rate = 44_100
        val pcm = ByteArray(rate * 10 * 2)
        for (i in 0 until rate * 10) {
            val v = (sin(2 * PI * 440 * i / rate) * 8000).toInt()
            pcm[i * 2] = (v and 0xff).toByte()
            pcm[i * 2 + 1] = (v shr 8).toByte()
        }
        val format = AudioFormat(rate.toFloat(), 16, 1, true, false)
        AudioSystem.write(AudioInputStream(ByteArrayInputStream(pcm), format, (rate * 10).toLong()), AudioFileFormat.Type.WAVE, wav.toFile())
    }

    @After
    fun tearDown() {
        engine.release()
        Files.deleteIfExists(wav)
    }

    private fun engine(failFirstWrite: Boolean = false) =
        AudioEngine(sinkFactory = { FakeSink(failFirstWrite && sinks.isEmpty()).also { sinks += it } }).also { engine = it }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    @Test
    fun playsToEndAndReportsEnded() {
        val e = engine()
        e.load(AudioSource.LocalFile(wav))
        assertTrue(waitFor(15_000) { e.state.value == EngineState.ENDED })
        val seconds = sinks.sumOf { it.bytes }.toDouble() / BYTES_PER_SECOND
        assertEquals(10.0, seconds, 0.2)
        assertEquals(10_000.0, e.durationMs.value.toDouble(), 100.0)
    }

    @Test
    fun seekJumpsToRequestedPosition() {
        val e = engine()
        e.load(AudioSource.LocalFile(wav))
        assertTrue(waitFor(5_000) { e.state.value == EngineState.PLAYING })
        e.seekTo(7_000)
        assertTrue(waitFor(2_000) { e.positionMs.value in 6_700..7_300 })
    }

    @Test
    fun pauseStopsWritingAndPlayResumes() {
        val e = engine()
        e.load(AudioSource.LocalFile(wav))
        assertTrue(waitFor(5_000) { e.state.value == EngineState.PLAYING && sinks.first().bytes > 0 })
        e.pause()
        assertTrue(waitFor(1_000) { e.state.value == EngineState.PAUSED })
        Thread.sleep(100)
        val bytes = sinks.first().bytes
        Thread.sleep(500)
        assertEquals(bytes, sinks.first().bytes)
        e.play()
        assertTrue(waitFor(2_000) { sinks.first().bytes > bytes })
    }

    @Test
    fun startPositionIsHonoured() {
        val e = engine()
        e.load(AudioSource.LocalFile(wav), startMs = 4_000, playWhenReady = false)
        assertTrue(waitFor(5_000) { e.state.value == EngineState.PAUSED })
        assertEquals(4_000.0, e.positionMs.value.toDouble(), 300.0)
    }

    @Test
    fun sinkFailureReopensOnce() {
        val e = engine(failFirstWrite = true)
        e.load(AudioSource.LocalFile(wav))
        assertTrue(waitFor(15_000) { e.state.value == EngineState.ENDED })
        assertEquals(2, sinks.size)
    }

    /** Like a real line: write() blocks while the line is stopped (paused) and returns once closed. */
    private class BlockingSink : PcmSink {
        @Volatile var closed = false
        @Volatile private var stopped = false
        private val lock = Object()
        override var volume = 1f
        override fun open(sampleRate: Int, channels: Int) = Unit
        override fun write(buf: ByteArray, len: Int) {
            // A real line is nearly always full, so the decoder sits inside write() when paused.
            Thread.sleep(len * 1000L / BYTES_PER_SECOND / 10)
            synchronized(lock) { while (stopped && !closed) lock.wait(50) }
            if (closed) throw IllegalStateException("line closed")
        }
        override fun flush() = Unit
        override fun pause() { stopped = true }
        override fun resume() { synchronized(lock) { stopped = false; lock.notifyAll() } }
        override fun close() { synchronized(lock) { closed = true; lock.notifyAll() } }
    }

    @Test
    fun loadingWhilePausedReleasesThePreviousDecoder() {
        val created = mutableListOf<BlockingSink>()
        val e = AudioEngine(sinkFactory = { BlockingSink().also { created += it } }).also { engine = it }
        e.load(AudioSource.LocalFile(wav))
        assertTrue(waitFor(5_000) { e.state.value == EngineState.PLAYING })
        Thread.sleep(200)
        e.pause()
        Thread.sleep(300)
        e.load(AudioSource.LocalFile(wav))
        assertTrue(waitFor(3_000) { created.size >= 2 })
        assertTrue("old line still open", waitFor(2_000) { created.first().closed })
        assertTrue("old decoder still alive", waitFor(2_000) { Thread.getAllStackTraces().keys.count { it.name.startsWith("vegas-decoder-") && it.isAlive } == 1 })
        assertEquals(1, created.count { !it.closed })
    }

    private companion object {
        const val BYTES_PER_SECOND = 48_000L * 2 * 2
    }
}
