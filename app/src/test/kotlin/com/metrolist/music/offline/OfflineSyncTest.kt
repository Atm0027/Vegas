package com.metrolist.music.offline

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.db.InternalDatabase
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.DownloadEntity
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.PlaylistSongMap
import com.metrolist.music.db.entities.SongEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OfflineSyncTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val internal = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).allowMainThreadQueries().build()
    private val database = MusicDatabase(internal)
    private val queue = RecordingQueue(context)
    private val sync = OfflineSync(database, queue, OfflineStore(context), context)

    @After
    fun close() = internal.close()

    class RecordingQueue(context: Context) : DownloadQueue(context) {
        val automatic = mutableListOf<Pair<Set<String>, Boolean>>()

        override fun enqueueAutomatic(songIds: Collection<String>, allowMobileData: Boolean) {
            automatic += songIds.toSet() to allowMobileData
        }
    }

    private fun trackedPlaylistWith(vararg songIds: String) = runBlocking {
        database.insert(PlaylistEntity(id = "P", name = "Paseo", isLocal = true))
        database.setPlaylistDownloaded("P", true)
        songIds.forEachIndexed { i, id ->
            database.insert(SongEntity(id = id, title = id))
            database.insert(PlaylistSongMap(playlistId = "P", songId = id, position = i))
        }
    }

    private fun download(songId: String, uri: Uri) = runBlocking {
        database.upsertDownload(DownloadEntity(songId, uri.toString(), "$songId.m4a", 1, "audio/mp4", LocalDateTime.now()))
    }

    @Test
    fun fileDeletedOutsideVega_isDownloadedAgain() = runBlocking {
        trackedPlaylistWith("A")
        val file = File.createTempFile("songA", ".m4a", context.cacheDir)
        download("A", Uri.fromFile(file))
        file.delete()

        assertEquals(OfflineSync.VerifyReport(missing = 1, noAccess = 0), sync.verifyFiles())
        assertNull(database.downloadEntity("A"))

        sync.pass(includeLiked = false, allowMobileData = false)
        assertEquals(setOf("A") to false, queue.automatic.last())
    }

    @Test
    fun fileWithoutReadPermission_isKeptAndNotQueued() = runBlocking {
        Robolectric.setupContentProvider(com.metrolist.music.offline.OfflineStoreTest.DenyingProvider::class.java, "vega.denied")
        trackedPlaylistWith("A")
        download("A", Uri.parse("content://vega.denied/audio/1"))

        assertEquals(OfflineSync.VerifyReport(missing = 0, noAccess = 1), sync.verifyFiles())
        assertNotNull(database.downloadEntity("A"))

        sync.pass(includeLiked = false, allowMobileData = true)
        assertEquals(emptySet<String>(), queue.automatic.lastOrNull()?.first.orEmpty())
    }

    @Test
    fun likedSongs_onlyQueuedWhenTracked() = runBlocking {
        database.insert(SongEntity(id = "L", title = "L", liked = true))
        sync.pass(includeLiked = false, allowMobileData = false)
        assertEquals(emptySet<String>(), queue.automatic.lastOrNull()?.first.orEmpty())
        sync.pass(includeLiked = true, allowMobileData = false)
        assertEquals(setOf("L"), queue.automatic.last().first)
    }
}
