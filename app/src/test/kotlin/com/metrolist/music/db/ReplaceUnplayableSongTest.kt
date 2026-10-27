package com.metrolist.music.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.PlaylistSongMap
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.music.models.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

/** Vegas: an upload that no longer plays is swapped in playlists for the upload that does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReplaceUnplayableSongTest {
    private lateinit var database: InternalDatabase
    private val likedAt = LocalDateTime.of(2026, 10, 1, 12, 0)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).allowMainThreadQueries().build()
        runBlocking {
            withContext(Dispatchers.IO) {
                database.dao.insert(PlaylistEntity(id = "list", name = "Torre"))
                database.dao.insert(SongEntity(id = "before", title = "Antes"))
                database.dao.insert(SongEntity(id = "broken", title = "She Like 2 Fuck", liked = true, likedDate = likedAt))
                database.dao.insert(SongEntity(id = "after", title = "Después"))
                listOf("before", "broken", "after").forEachIndexed { position, id ->
                    database.dao.insert(PlaylistSongMap(playlistId = "list", songId = id, position = position))
                }
            }
        }
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `swaps the song in place and keeps the like`() = runBlocking {
        withContext(Dispatchers.IO) {
            database.dao.replaceUnplayableSong(
                "broken",
                MediaMetadata(id = "works", title = "She Like 2 Fuck", artists = emptyList(), duration = 386),
            )
        }

        assertEquals(listOf("before", "works", "after"), database.dao.playlistSongIds("list"))
        val replacement = withContext(Dispatchers.IO) { database.dao.songEntityBlocking("works") }!!
        assertTrue(replacement.liked)
        assertEquals(likedAt, replacement.likedDate)
    }
}
