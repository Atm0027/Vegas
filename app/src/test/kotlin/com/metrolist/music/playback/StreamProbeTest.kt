package com.metrolist.music.playback

import com.metrolist.music.playback.StreamProbe.Verdict
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamProbeTest {
    @Test
    fun verdict_readsHttpCodes() {
        assertEquals(Verdict.REJECTED, StreamProbe.verdict(403))
        assertEquals(Verdict.REJECTED, StreamProbe.verdict(410))
        assertEquals(Verdict.PLAYABLE, StreamProbe.verdict(206))
        assertEquals(Verdict.PLAYABLE, StreamProbe.verdict(200))
        assertEquals(Verdict.UNKNOWN, StreamProbe.verdict(500))
    }

    @Test
    fun refusedStream_triesNextClientWithoutReachingThePlayer() = runBlocking {
        val streams = ArrayDeque(listOf("web", "tv"))
        val rejected = mutableListOf<String>()
        val result = StreamProbe.resolveVerified(
            resolve = { Result.success(streams.removeFirst()) },
            probe = { if (it == "web") Verdict.REJECTED else Verdict.PLAYABLE },
            onRejected = { rejected += it },
        )
        assertEquals("tv", result.getOrNull())
        assertEquals(listOf("web"), rejected)
    }

    @Test
    fun unknownProbe_playsAsBefore() = runBlocking {
        var resolves = 0
        val result = StreamProbe.resolveVerified(
            resolve = { resolves++; Result.success("web") },
            probe = { Verdict.UNKNOWN },
            onRejected = { error("not rejected") },
        )
        assertEquals("web", result.getOrNull())
        assertEquals(1, resolves)
    }

    @Test
    fun everyClientRefused_handsLastStreamToPlayerAfterLimit() = runBlocking {
        var resolves = 0
        val result = StreamProbe.resolveVerified(
            maxRejections = 2,
            resolve = { resolves++; Result.success("s$resolves") },
            probe = { Verdict.REJECTED },
            onRejected = {},
        )
        assertEquals("s3", result.getOrNull())
        assertEquals(3, resolves)
    }

    @Test
    fun noOtherClient_keepsTheRefusedStream() = runBlocking {
        val streams = ArrayDeque(listOf(Result.success("web"), Result.failure(IllegalStateException("none"))))
        val result = StreamProbe.resolveVerified(
            resolve = { streams.removeFirst() },
            probe = { Verdict.REJECTED },
            onRejected = {},
        )
        assertEquals("web", result.getOrNull())
    }

    @Test
    fun firstFailure_isReturned() = runBlocking {
        val boom = IllegalStateException("boom")
        val result = StreamProbe.resolveVerified<String>(
            resolve = { Result.failure(boom) },
            probe = { Verdict.PLAYABLE },
            onRejected = {},
        )
        assertEquals(boom, result.exceptionOrNull())
    }

    @Test
    fun rangeHeader_matchesThePlayerRequest() {
        assertEquals("bytes=0-999", StreamProbe.rangeHeader(1_000L))
        assertEquals("bytes=0-", StreamProbe.rangeHeader(null))
        assertEquals("bytes=0-", StreamProbe.rangeHeader(0L))
    }
}
