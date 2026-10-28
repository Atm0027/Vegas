package com.metrolist.music.utils

import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.db.entities.SongArtistMap
import com.metrolist.music.db.entities.SongEntity
import com.metrolist.music.models.MediaMetadata
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class SongMetadataRefreshTest {
    private val liked = LocalDateTime.of(2026, 10, 1, 12, 0)
    private val old =
        SongEntity(
            id = "a",
            title = "Old title",
            duration = 200,
            thumbnailUrl = "old.jpg",
            albumId = "MPRE1",
            albumName = "Old album",
            explicit = true,
            liked = true,
            likedDate = liked,
            inLibrary = liked,
            totalPlayTime = 5_000,
            isDownloaded = true,
        )

    private fun meta(
        id: String = "a",
        title: String = "New title",
        duration: Int = 210,
        thumbnailUrl: String? = "new.jpg",
        album: MediaMetadata.Album? = MediaMetadata.Album("MPRE2", "New album"),
    ) = MediaMetadata(id = id, title = title, artists = emptyList(), duration = duration, thumbnailUrl = thumbnailUrl, album = album)

    @Test
    fun `takes fresh metadata and keeps the user's data`() {
        val merged = SongMetadataRefresh.merge(old, meta())
        assertEquals("New title", merged.title)
        assertEquals(210, merged.duration)
        assertEquals("new.jpg", merged.thumbnailUrl)
        assertEquals("MPRE2", merged.albumId)
        assertEquals("New album", merged.albumName)
        assertEquals(old.copy(title = merged.title, duration = 210, thumbnailUrl = "new.jpg", albumId = "MPRE2", albumName = "New album"), merged)
    }

    @Test
    fun `keeps old values where YouTube sent nothing`() {
        val merged = SongMetadataRefresh.merge(old.copy(explicit = true), meta(title = " ", duration = -1, thumbnailUrl = null, album = null))
        assertEquals(old, merged)
    }

    @Test
    fun `batches, ignores unknown ids and counts what was saved`() = runBlocking {
        val ids = (1..120).map { "s$it" }
        val asked = mutableListOf<List<String>>()
        val saved = mutableListOf<String>()
        val outcome =
            SongMetadataRefresh.run(
                ids = ids,
                fetch = { batch ->
                    asked += batch
                    batch.filter { it != "s7" }.map { meta(id = it) } + meta(id = "other")
                },
                apply = { saved += it.id; true },
            )
        assertEquals(listOf(50, 50, 20), asked.map { it.size })
        assertEquals(119, outcome.updated)
        assertEquals(1, outcome.notFound)
        assertTrue("other" !in saved)
    }

    @Test
    fun `stops after three failed requests in a row`() = runBlocking {
        var calls = 0
        val outcome =
            SongMetadataRefresh.run(
                ids = (1..500).map { "s$it" },
                fetch = { calls++; null },
                apply = { true },
            )
        assertEquals(3, calls)
        assertTrue(outcome.stoppedEarly)
        assertEquals(500, outcome.notFound)
    }

    private fun saved(vararg artists: ArtistEntity) =
        Song(
            song = old,
            artists = artists.toList(),
            artistMaps = artists.mapIndexed { i, a -> SongArtistMap(songId = "a", artistId = a.id, position = i) },
        )

    @Test
    fun `drops counts from fresh artists`() {
        val fresh = meta().copy(artists = listOf(MediaMetadata.Artist("UC1", "Clarent"), MediaMetadata.Artist(null, "2,4\u00a0M reproducciones")))
        assertEquals(listOf(MediaMetadata.Artist("UC1", "Clarent")), SongMetadataRefresh.artistsToSave(saved(), fresh))
    }

    @Test
    fun `cleans saved counts when YouTube sends no artists`() {
        val song = saved(ArtistEntity("UC1", "Clarent"), ArtistEntity("LA1", "2,4\u00a0M reproducciones"))
        assertEquals(listOf(MediaMetadata.Artist("UC1", "Clarent")), SongMetadataRefresh.artistsToSave(song, meta()))
        assertEquals(null, SongMetadataRefresh.artistsToSave(saved(ArtistEntity("UC1", "Clarent")), meta()))
    }
}
