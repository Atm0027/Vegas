package com.metrolist.music.playback

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

class StreamResolveTimeoutTest {
    @Test
    fun hungResolution_failsWithSocketTimeout() = runBlocking {
        val result = resolveWithTimeout(50L, "abc") {
            delay(5_000L)
            Result.success(1)
        }
        assertTrue(result.exceptionOrNull() is SocketTimeoutException)
    }

    @Test
    fun fastResolution_passesThrough() = runBlocking {
        val result = resolveWithTimeout(1_000L, "abc") { Result.success(7) }
        assertEquals(7, result.getOrNull())
    }

    @Test
    fun failedResolution_keepsOriginalError() = runBlocking {
        val boom = IllegalStateException("boom")
        val result = resolveWithTimeout(1_000L, "abc") { Result.failure<Int>(boom) }
        assertEquals(boom, result.exceptionOrNull())
    }
}
