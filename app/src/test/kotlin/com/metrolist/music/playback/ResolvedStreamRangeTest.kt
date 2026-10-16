package com.metrolist.music.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ResolvedStreamRangeTest {
    private val stream = CachedStreamUrl(url = "https://rr1.googlevideo.com/videoplayback?id=x", requestHeaders = emptyMap(), clientName = "VISIONOS")

    private fun spec(position: Long, length: Long = C.LENGTH_UNSET.toLong()) =
        DataSpec.Builder().setUri(Uri.parse("song://x")).setPosition(position).setLength(length).setKey("x").build()

    @Test
    fun openEndedRequest_isBoundedToEndOfFile_fromStart() {
        val resolved = spec(0).withResolvedStream(stream, contentLength = 5_000_000L)
        assertEquals(0L, resolved.position)
        assertEquals(5_000_000L, resolved.length)
    }

    @Test
    fun openEndedRequest_isBoundedToEndOfFile_afterSeek() {
        val resolved = spec(1_200_000).withResolvedStream(stream, contentLength = 5_000_000L)
        assertEquals(1_200_000L, resolved.position)
        assertEquals(3_800_000L, resolved.length)
    }

    @Test
    fun unknownLength_staysOpenEnded() {
        val resolved = spec(0).withResolvedStream(stream, contentLength = null)
        assertEquals(C.LENGTH_UNSET.toLong(), resolved.length)
    }

    @Test
    fun alreadyBoundedRequest_isUnchanged() {
        val resolved = spec(100, length = 50).withResolvedStream(stream, contentLength = 5_000_000L)
        assertEquals(100L, resolved.position)
        assertEquals(50L, resolved.length)
    }

    @Test
    fun chunkPolicy_stillWins() {
        val chunked = stream.copy(useRangeChunks = true, rangeChunkSizeBytes = 1_000L)
        val resolved = spec(0).withResolvedStream(chunked, contentLength = 5_000_000L)
        assertEquals(1_000L, resolved.length)
    }

    @Test
    fun positionAtOrPastEnd_staysOpenEnded() {
        val resolved = spec(5_000_000).withResolvedStream(stream, contentLength = 5_000_000L)
        assertEquals(C.LENGTH_UNSET.toLong(), resolved.length)
    }
}
