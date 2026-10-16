package com.metrolist.music.playback

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LocalFirstDataSourceTest {
    private class Recording(val name: String, val log: MutableList<String>) : DataSource.Factory {
        override fun createDataSource(): DataSource = object : DataSource {
            override fun addTransferListener(transferListener: TransferListener) = Unit
            override fun open(dataSpec: DataSpec): Long {
                log += "$name:${dataSpec.uri.scheme}"
                return 0
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int) = -1
            override fun getUri(): Uri? = null
            override fun close() = Unit
        }
    }

    @Test
    fun localUris_areRecognised() {
        assertTrue(LocalFirstDataSource.isLocal(Uri.parse("content://media/external/audio/media/5")))
        assertTrue(LocalFirstDataSource.isLocal(Uri.parse("file:///x.m4a")))
        assertFalse(LocalFirstDataSource.isLocal(Uri.parse("https://rr1---sn.googlevideo.com/videoplayback?x=1")))
    }

    @Test
    fun downloadedFiles_openLocally_andStreams_openRemotely() {
        val log = mutableListOf<String>()
        val source = LocalFirstDataSource.Factory(Recording("local", log), Recording("remote", log)).createDataSource()
        source.open(DataSpec(Uri.parse("content://media/external/audio/media/5")))
        source.close()
        source.open(DataSpec(Uri.parse("https://rr1---sn.googlevideo.com/videoplayback")))
        source.close()
        assertEquals(listOf("local:content", "remote:https"), log)
    }
}
