package com.metrolist.music.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.db.entities.Playlist
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.PlaylistSongMap
import com.metrolist.music.db.entities.SongEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Vegas: the Android Auto quick-add button toggles, so repeated presses never pile up copies. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class QuickAddToggleTest {
    private lateinit var database: InternalDatabase
    private val entity = PlaylistEntity(id = "target", name = "Coche")
    private val playlist get() = Playlist(entity, 0, emptyList())

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).allowMainThreadQueries().build()
        runBlocking {
            withContext(Dispatchers.IO) {
                database.dao.insert(entity)
                listOf("song", "other").forEach { database.dao.insert(SongEntity(id = it, title = it)) }
            }
        }
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `first press adds the song, second press removes it`() = runBlocking {
        assertTrue(database.dao.toggleSongInPlaylist(playlist, "song"))
        assertEquals(listOf("song"), database.dao.playlistSongIds("target"))
        assertTrue(database.dao.isSongInPlaylist("target", "song").first())

        assertFalse(database.dao.toggleSongInPlaylist(playlist, "song"))
        assertEquals(emptyList<String>(), database.dao.playlistSongIds("target"))
        assertFalse(database.dao.isSongInPlaylist("target", "song").first())
    }

    @Test
    fun `removing clears every copy and leaves other songs alone`() = runBlocking {
        withContext(Dispatchers.IO) {
            database.dao.insert(PlaylistSongMap(playlistId = "target", songId = "other", position = 0))
            repeat(3) { database.dao.insert(PlaylistSongMap(playlistId = "target", songId = "song", position = it + 1)) }
        }
        assertFalse(database.dao.toggleSongInPlaylist(playlist, "song"))
        assertEquals(listOf("other"), database.dao.playlistSongIds("target"))
    }
}
