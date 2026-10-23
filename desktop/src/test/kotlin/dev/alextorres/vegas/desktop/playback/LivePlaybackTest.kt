package dev.alextorres.vegas.desktop.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import java.nio.file.Files

/** End to end on the real network and sound card: resolve, decode and play a few seconds. */
class LivePlaybackTest {
    @Test
    @Ignore("live network and audio device; run manually")
    fun playsARealSongAndSeeks() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = AudioEngine().apply { volume = 0.15f }
        val controller = PlayerController(scope, StreamResolver.live(), engine, { emptyList() }, QueueStore(Files.createTempFile("q", ".json")))
        try {
            controller.playQueue(listOf(Track("fJ9rUzIMcZQ", "Bohemian Rhapsody", "Queen", null, null, null)), 0)
            val start = System.currentTimeMillis()
            while (controller.state.value.positionMs < 3_000 && System.currentTimeMillis() - start < 20_000) Thread.sleep(100)
            println("LIVE position=${controller.state.value.positionMs} duration=${controller.state.value.durationMs} playing=${controller.state.value.isPlaying}")
            assertTrue(controller.state.value.positionMs >= 3_000)
            controller.seekTo(120_000)
            Thread.sleep(2_000)
            println("LIVE after seek position=${controller.state.value.positionMs}")
            assertTrue(controller.state.value.positionMs in 120_000..125_000)
        } finally {
            engine.release()
            scope.cancel()
        }
    }
}
