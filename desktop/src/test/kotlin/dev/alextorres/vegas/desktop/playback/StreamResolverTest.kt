package dev.alextorres.vegas.desktop.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

class StreamResolverTest {
    private var now = 1_000_000L
    private val calls = mutableListOf<Set<String>>()
    private var failWith: Throwable? = null

    private val expiresAt = now + 600_000

    private val resolver = StreamResolver(
        extract = { id, excluded ->
            calls += excluded
            failWith?.let { throw it }
            ResolvedStream(id, "https://example/$id", emptyMap(), "audio/webm", expiresAt, "VISIONOS", null)
        },
        now = { now },
    )

    @Test
    fun cachedUrlIsReusedUntilAMinuteBeforeExpiry() = runBlocking {
        resolver.resolve("v")
        now = expiresAt - 60_001
        resolver.resolve("v")
        assertEquals(1, calls.size)
        now = expiresAt - 59_000
        resolver.resolve("v")
        assertEquals(2, calls.size)
    }

    @Test
    fun failedClientIsExcludedForFiveMinutes() = runBlocking {
        resolver.markFailed("v", "VISIONOS")
        resolver.resolve("v")
        assertEquals(setOf("VISIONOS"), calls.last())
        resolver.invalidate("v")
        now += 300_001
        resolver.resolve("v")
        assertEquals(emptySet<String>(), calls.last())
    }

    @Test
    fun markingFailedDropsTheCachedUrl() = runBlocking {
        resolver.resolve("v")
        resolver.markFailed("v", "VISIONOS")
        resolver.resolve("v")
        assertEquals(2, calls.size)
    }

    @Test
    fun extractionErrorBecomesFailure() = runBlocking {
        failWith = IllegalStateException("no playable stream")
        assertTrue(resolver.resolve("v").isFailure)
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsRethrown() {
        runBlocking {
            failWith = CancellationException("cancelled")
            resolver.resolve("v")
        }
    }

    @Test
    @Ignore("live network; run manually")
    fun liveResolve() = runBlocking {
        val stream = StreamResolver.live().resolve("fJ9rUzIMcZQ").getOrThrow()
        assertTrue(stream.url.startsWith("https://"))
    }
}
