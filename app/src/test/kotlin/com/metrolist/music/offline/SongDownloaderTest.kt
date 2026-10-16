package com.metrolist.music.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SongDownloaderTest {
    @Test
    fun `ranged url covers the whole file once`() {
        assertEquals("https://x/videoplayback?a=1&range=0-999", rangedUrl("https://x/videoplayback?a=1", 1000))
        assertEquals("https://x/v?a=1&range=0-5", rangedUrl("https://x/v?a=1&range=0-5", 1000))
    }

    @Test
    fun `resume header starts after the bytes already saved`() {
        assertEquals("bytes=1000-", resumeHeader(existing = 1000))
    }

    @Test
    fun `incomplete download is discarded`() {
        val part = File.createTempFile("song", ".part").apply { writeBytes(ByteArray(999)) }
        assertFalse(verifySize(part, expected = 1000))
        assertFalse(part.exists())
    }

    @Test
    fun `complete download is kept`() {
        val part = File.createTempFile("song", ".part").apply { writeBytes(ByteArray(1000)) }
        assertTrue(verifySize(part, expected = 1000))
        assertTrue(part.exists())
        part.delete()
    }

    @Test
    fun `extension follows the mime type`() {
        assertEquals("m4a", extFor("audio/mp4"))
        assertEquals("webm", extFor("audio/webm"))
    }
}
