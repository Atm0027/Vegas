package com.metrolist.music.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.PlaylistSongMap
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.music.playlist.PlaylistEditSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ApplyPlaylistEditTest {
    private lateinit var database: InternalDatabase
    private val dao get() = database.dao
    private val playlist =
        PlaylistEntity(id = "p", name = "Torre", thumbnailUrl = null, bookmarkedAt = LocalDateTime.of(2026, 1, 1, 0, 0))

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).allowMainThreadQueries().build()
        dao.insert(playlist)
        listOf("a", "b", "c", "d", "e").forEachIndexed { i, id ->
            dao.insert(SongEntity(id = id, title = id))
            dao.insert(PlaylistSongMap(playlistId = "p", songId = id, position = i))
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun maps() = dao.playlistSongMaps("p", 0)

    @Test
    fun applyEdit_reordersRemovesAndRenamesAtomically() =
        runBlocking {
            val ids = maps().map { it.id }
            val session = PlaylistEditSession(ids, "Torre", null)
            session.toggleRemove(ids[1]) // b
            session.toggleRemove(ids[3]) // d
            session.move(0, 2) // a after e -> c, e, a
            session.rename("Torre 2")
            session.setThumbnail("content://cover")

            dao.applyPlaylistEdit("p", session.commit())

            assertEquals(listOf("c", "e", "a"), dao.playlistSongIds("p"))
            assertEquals(listOf(0, 1, 2), maps().map { it.position })
            val saved = dao.playlist("p").first()!!.playlist
            assertEquals("Torre 2", saved.name)
            assertEquals("content://cover", saved.thumbnailUrl)
        }

    @Test
    fun restoreSnapshot_undoesTheEdit() =
        runBlocking {
            val snapshot = dao.playlistSnapshot("p")!!
            val ids = maps().map { it.id }
            val session = PlaylistEditSession(ids, "Torre", null)
            session.toggleRemove(ids[0])
            session.move(0, 3)
            session.rename("Otra")
            dao.applyPlaylistEdit("p", session.commit())

            dao.restorePlaylistSnapshot(snapshot)

            assertEquals(listOf("a", "b", "c", "d", "e"), dao.playlistSongIds("p"))
            assertEquals(listOf(0, 1, 2, 3, 4), maps().map { it.position })
            assertEquals("Torre", dao.playlist("p").first()!!.playlist.name)
        }
}
