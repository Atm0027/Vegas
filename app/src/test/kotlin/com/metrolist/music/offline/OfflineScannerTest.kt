package com.metrolist.music.offline

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.db.InternalDatabase
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.SongEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OfflineScannerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val internal = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).allowMainThreadQueries().build()
    private val database = MusicDatabase(internal)
    private val scanner = OfflineScanner(database, OfflineStore(context))

    @After
    fun close() = internal.close()

    private fun file(name: String, id: String) =
        OfflineStore.ScannedFile(Uri.parse("content://media/external/audio/media/${id.hashCode()}"), name, id, 1000, "audio/mp4", 215_000)

    @Test
    fun unknownSong_isCreatedFromTheFileName() = runBlocking {
        val report = scanner.link(listOf(file("Estopa - Como Camarón [abc123DEF_-].m4a", "abc123DEF_-")))

        assertEquals(OfflineScanner.ScanReport(linked = 1, newSongs = 1), report)
        val song = database.getSongById("abc123DEF_-")!!
        assertEquals("Como Camarón", song.song.title)
        assertEquals(215, song.song.duration)
        assertEquals(listOf("Estopa"), song.artists.map { it.name })
        assertEquals("abc123DEF_-", database.downloadEntity("abc123DEF_-")!!.songId)
        assertEquals(true, song.song.isDownloaded)
    }

    @Test
    fun knownSong_onlyGetsTheDownloadRow() = runBlocking {
        database.insert(SongEntity(id = "abc123DEF_-", title = "Título bueno"))
        val report = scanner.link(listOf(file("Otro - Otro título [abc123DEF_-].m4a", "abc123DEF_-")))

        assertEquals(OfflineScanner.ScanReport(linked = 1, newSongs = 0), report)
        assertEquals("Título bueno", database.getSongById("abc123DEF_-")!!.song.title)
        assertEquals("Otro - Otro título [abc123DEF_-].m4a", database.downloadEntity("abc123DEF_-")!!.fileName)
    }
}
