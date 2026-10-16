package com.metrolist.music.offline

import androidx.media3.exoplayer.offline.Download
import com.metrolist.music.db.entities.DownloadEntity
import com.metrolist.music.playback.combineDownloads
import com.metrolist.music.playback.toExoDownload
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DownloadFacadeStateTest {
    @Test
    fun downloadingProgress_isReportedAsPercent() {
        val download = toExoDownload("a", Download.STATE_DOWNLOADING, 0.5f, 0)
        assertEquals(50f, download.percentDownloaded, 0.01f)
        assertEquals("a", download.request.id)
    }

    @Test
    fun filesAndQueue_combineIntoOneMap() {
        val files = listOf(DownloadEntity("a", "content://x/1", "a.m4a", 100, "audio/mp4", LocalDateTime.now()))
        val queue = mapOf("b" to QueueState(running = false, progress = 0f), "c" to QueueState(running = true, progress = 0.3f))
        val map = combineDownloads(files, queue)
        assertEquals(Download.STATE_COMPLETED, map.getValue("a").state)
        assertEquals(Download.STATE_QUEUED, map.getValue("b").state)
        assertEquals(Download.STATE_DOWNLOADING, map.getValue("c").state)
    }

    @Test
    fun finishedFile_winsOverStaleQueueEntry() {
        val files = listOf(DownloadEntity("a", "content://x/1", "a.m4a", 100, "audio/mp4", LocalDateTime.now()))
        val map = combineDownloads(files, mapOf("a" to QueueState(running = true, progress = 0.99f)))
        assertEquals(Download.STATE_COMPLETED, map.getValue("a").state)
    }

    @Test
    fun playlistState_followsTheDownloadedMark() {
        val done = mapOf("a" to toExoDownload("a", Download.STATE_COMPLETED, 1f, 1), "b" to toExoDownload("b", Download.STATE_COMPLETED, 1f, 1))
        assertEquals(Download.STATE_STOPPED, playlistDownloadState(marked = false, songIds = listOf("a", "b"), downloads = done))
        assertEquals(Download.STATE_COMPLETED, playlistDownloadState(marked = true, songIds = listOf("a", "b"), downloads = done))
        assertEquals(Download.STATE_DOWNLOADING, playlistDownloadState(marked = true, songIds = listOf("a", "c"), downloads = done))
    }
}
