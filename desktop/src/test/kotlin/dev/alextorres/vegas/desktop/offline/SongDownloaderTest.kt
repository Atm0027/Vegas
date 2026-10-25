package dev.alextorres.vegas.desktop.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
        assertEquals("m4a", OfflineFolder.extFor("audio/mp4"))
        assertEquals("webm", OfflineFolder.extFor("audio/webm"))
    }

    @Test
    fun partFile_isTiedToFormatAndLength() {
        assertEquals("abc-audio_mp4-1000.part", partName("abc", "audio/mp4", 1000))
        assertNotEquals(partName("abc", "audio/mp4", 1000), partName("abc", "audio/webm", 1000))
        assertNotEquals(partName("abc", "audio/mp4", 1000), partName("abc", "audio/mp4", 1200))
    }

    @Test
    fun resume_appendsOnlyWhenTheServerContinuesWhereThePartEnds() {
        assertEquals(Resume.APPEND, resumeMode(206, existing = 400, contentRange = "bytes 400-999/1000", contentLength = 600, length = 1000))
        assertEquals(Resume.APPEND, resumeMode(200, existing = 400, contentRange = null, contentLength = 600, length = 1000))
        assertEquals(Resume.RESTART, resumeMode(200, existing = 400, contentRange = null, contentLength = 1000, length = 1000))
        assertEquals(Resume.RESTART, resumeMode(200, existing = 0, contentRange = null, contentLength = 1000, length = 1000))
        assertEquals(Resume.MISMATCH, resumeMode(206, existing = 400, contentRange = "bytes 0-999/1000", contentLength = 1000, length = 1000))
        assertEquals(Resume.MISMATCH, resumeMode(200, existing = 400, contentRange = null, contentLength = 700, length = 1000))
    }
}
