package dev.alextorres.vegas.desktop.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class PlayerControllerTest {
    private class FakeEngine : PlaybackEngine {
        override val state = MutableStateFlow(EngineState.IDLE)
        override val positionMs = MutableStateFlow(0L)
        override val durationMs = MutableStateFlow(0L)
        override val errors = MutableSharedFlow<Throwable>(extraBufferCapacity = 8)
        override var volume = 1f
        val loads = mutableListOf<Pair<String, Long>>()
        var plays = 0

        override fun load(source: AudioSource, startMs: Long, playWhenReady: Boolean) {
            loads += (source as AudioSource.Remote).stream.videoId to startMs
            positionMs.value = startMs
            state.value = if (playWhenReady) EngineState.PLAYING else EngineState.PAUSED
        }
        override fun play() { plays++; state.value = EngineState.PLAYING }
        override fun pause() { state.value = EngineState.PAUSED }
        override fun seekTo(ms: Long) { positionMs.value = ms }
        override fun release() { state.value = EngineState.IDLE }
        fun end() { state.value = EngineState.ENDED }
    }

    private var now = 0L
    private val unplayable = mutableSetOf<String>()
    private val extracted = mutableListOf<String>()
    private val resolver = StreamResolver(
        extract = { id, _ ->
            extracted += id
            if (id in unplayable) error("unavailable")
            ResolvedStream(id, "https://x/$id", emptyMap(), "audio/webm", now + 600_000, "VISIONOS", null, durationMs = 225_000)
        },
        now = { now },
    )
    private val engine = FakeEngine()
    private var related: List<Track> = emptyList()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val queueFile = Files.createTempFile("queue", ".json")

    private fun track(id: String) = Track(id, "Song $id", "Artist", null, null, 180_000)

    private fun controller(store: QueueStore = QueueStore(queueFile)) =
        PlayerController(scope, resolver, engine, related = { related }, store = store)

    @After
    fun tearDown() {
        scope.cancel()
        Files.deleteIfExists(queueFile)
    }

    @Test
    fun nextAndPreviousMoveThroughQueue() {
        val c = controller()
        c.playQueue(listOf(track("a"), track("b"), track("c")), startIndex = 0)
        c.next()
        assertEquals(1, c.state.value.index)
        c.previous()
        assertEquals(0, c.state.value.index)
        assertEquals(listOf("a", "b", "a"), engine.loads.map { it.first })
    }

    @Test
    fun previousAfterThreeSecondsRestartsSong() {
        val c = controller()
        c.playQueue(listOf(track("a"), track("b")), startIndex = 1)
        engine.positionMs.value = 3_500
        c.previous()
        assertEquals(1, c.state.value.index)
        assertEquals(0L, engine.positionMs.value)
    }

    @Test
    fun repeatOneReplaysSameTrack() {
        val c = controller()
        c.playQueue(listOf(track("a"), track("b")), startIndex = 0)
        c.cycleRepeat()
        c.cycleRepeat()
        assertEquals(RepeatMode.ONE, c.state.value.repeat)
        engine.end()
        assertEquals(0, c.state.value.index)
        assertEquals(listOf("a", "a"), engine.loads.map { it.first })
    }

    @Test
    fun endOfQueueAppendsRelatedTracks() {
        related = listOf(track("a"), track("x"), track("y"))
        val c = controller()
        c.playQueue(listOf(track("a")), startIndex = 0)
        engine.end()
        assertEquals(listOf("a", "x", "y"), c.state.value.queue.map { it.id })
        assertEquals(1, c.state.value.index)
    }

    @Test
    fun unplayableTrackIsSkippedWithMessage() {
        unplayable += "b"
        val c = controller()
        c.playQueue(listOf(track("a"), track("b"), track("c")), startIndex = 1)
        assertEquals(2, c.state.value.index)
        assertEquals("No se puede reproducir «Song b»", c.state.value.message)
        assertEquals(listOf("c"), engine.loads.map { it.first })
    }

    @Test
    fun streamErrorRetriesOnceAtSamePosition() {
        val c = controller()
        c.playQueue(listOf(track("a")), startIndex = 0)
        engine.positionMs.value = 42_000
        engine.errors.tryEmit(RuntimeException("connection reset"))
        assertEquals("a" to 42_000L, engine.loads.last())
        assertEquals(2, extracted.size)
        assertTrue(c.state.value.isPlaying)
    }

    @Test
    fun secondStreamErrorPausesWithOfflineMessage() {
        val c = controller()
        c.playQueue(listOf(track("a"), track("b")), startIndex = 0)
        engine.errors.tryEmit(RuntimeException("offline"))
        engine.errors.tryEmit(RuntimeException("offline"))
        assertFalse(c.state.value.isPlaying)
        assertEquals(0, c.state.value.index)
        assertEquals("Sin conexión", c.state.value.message)
    }

    @Test
    fun resumingAfterExpiryResolvesAgain() {
        val c = controller()
        c.playQueue(listOf(track("a")), startIndex = 0)
        engine.positionMs.value = 30_000
        c.togglePlay()
        now += 600_000
        c.togglePlay()
        assertEquals(2, extracted.size)
        assertEquals("a" to 30_000L, engine.loads.last())
        assertTrue(c.state.value.isPlaying)
    }

    @Test
    fun resumingBeforeExpiryJustPlays() {
        val c = controller()
        c.playQueue(listOf(track("a")), startIndex = 0)
        c.togglePlay()
        c.togglePlay()
        assertEquals(1, engine.loads.size)
        assertEquals(1, engine.plays)
    }

    @Test
    fun queueIsRestoredPausedOnStartup() {
        val first = controller()
        first.playQueue(listOf(track("a"), track("b")), startIndex = 1)
        engine.positionMs.value = 61_000
        first.togglePlay()

        val restored = controller(QueueStore(queueFile))
        assertEquals(listOf("a", "b"), restored.state.value.queue.map { it.id })
        assertEquals(1, restored.state.value.index)
        assertEquals(61_000L, restored.state.value.positionMs)
        assertFalse(restored.state.value.isPlaying)
    }

    @Test
    fun corruptQueueFileIsIgnored() {
        Files.writeString(queueFile, "{not json")
        assertNull(QueueStore(queueFile).load())
        assertNotNull(controller().state.value)
    }

    @Test
    fun shuffleKeepsCurrentTrackFirst() {
        val c = controller()
        val tracks = (1..20).map { track("t$it") }
        c.playQueue(tracks, startIndex = 5, shuffle = true)
        assertEquals("t6", c.state.value.queue[c.state.value.index].id)
        assertEquals(tracks.map { it.id }.toSet(), c.state.value.queue.map { it.id }.toSet())
        c.setShuffle(false)
        assertEquals(tracks.map { it.id }, c.state.value.queue.map { it.id })
        assertEquals("t6", c.state.value.queue[c.state.value.index].id)
    }

    @Test
    fun playAtJumpsToThatQueueEntry() {
        val c = controller()
        c.playQueue(listOf(track("a"), track("b"), track("c")), startIndex = 0)
        c.playAt(2)
        assertEquals(2, c.state.value.index)
        assertEquals("c", engine.loads.last().first)
    }

    @Test
    fun durationFallsBackToStreamMetadataWhenTheDecoderHasNone() {
        val c = controller()
        c.playQueue(listOf(Track("a", "Song a", "Artist", null, null, null)), startIndex = 0)
        engine.durationMs.value = 0
        assertEquals(225_000L, c.state.value.durationMs)
        engine.durationMs.value = 226_000
        assertEquals(226_000L, c.state.value.durationMs)
    }

    @Test
    fun persistOnCloseKeepsThePlayingPosition() {
        val c = controller()
        c.playQueue(listOf(track("a"), track("b")), startIndex = 0)
        engine.positionMs.value = 95_000
        c.persist()
        assertEquals(95_000L, QueueStore(queueFile).load()?.positionMs)
    }
}
