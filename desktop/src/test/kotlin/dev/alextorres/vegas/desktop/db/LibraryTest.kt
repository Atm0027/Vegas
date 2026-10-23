package dev.alextorres.vegas.desktop.db

import dev.alextorres.vegas.desktop.playback.Track
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTest {
    private val db = openInMemoryDatabase()
    private val library = Library(db)

    @After
    fun tearDown() = db.close()

    private fun track(id: String) = Track(id, "Song $id", "Artist", null, null, 180_000)

    private suspend fun order(playlistId: String) = library.entries(playlistId).first().map { it.song.id }

    @Test
    fun pinnedPlaylistsComeFirst() = runBlocking {
        library.createPlaylist("Alpha")
        val rock = library.createPlaylist("Rock")
        library.createPlaylist("Beta")
        library.setPinned(rock, true)
        assertEquals(listOf("Rock", "Alpha", "Beta"), library.playlists().first().map { it.name })
    }

    @Test
    fun customOrderIsKeptAfterPinned() = runBlocking {
        val a = library.createPlaylist("A")
        val b = library.createPlaylist("B")
        val c = library.createPlaylist("C")
        library.reorderPlaylists(listOf(c, a, b))
        assertEquals(listOf("C", "A", "B"), library.playlists().first().map { it.name })
    }

    @Test
    fun addTracksReportsDuplicatesAndSkipsThem() = runBlocking {
        val p = library.createPlaylist("P")
        library.addTracks(p, listOf(track("a"), track("b")))
        val result = library.addTracks(p, listOf(track("b"), track("c")))
        assertEquals(1, result.added)
        assertEquals(listOf("b"), result.duplicates.map { it.id })
        assertEquals(listOf("a", "b", "c"), order(p))
    }

    @Test
    fun forceAddsDuplicates() = runBlocking {
        val p = library.createPlaylist("P")
        library.addTracks(p, listOf(track("a")))
        library.addTracks(p, listOf(track("a")), force = true)
        assertEquals(listOf("a", "a"), order(p))
    }

    @Test
    fun moveEntryKeepsOrder() = runBlocking {
        val p = library.createPlaylist("Torre")
        val tracks = (0 until 306).map { track("t$it") }
        library.addTracks(p, tracks)
        val entry300 = library.entries(p).first()[300].entryId
        library.moveEntry(p, entry300, 0)
        val expected = listOf("t300") + tracks.map { it.id }.filter { it != "t300" }
        assertEquals(expected, order(p))
    }

    @Test
    fun repeatedMovesRenumberWhenKeysGetTooClose() = runBlocking {
        val p = library.createPlaylist("P")
        library.addTracks(p, (0 until 5).map { track("t$it") })
        repeat(60) { i ->
            val entries = library.entries(p).first()
            library.moveEntry(p, entries[if (i % 2 == 0) 4 else 3].entryId, 1)
        }
        val ids = order(p)
        assertEquals(5, ids.toSet().size)
        assertEquals("t0", ids.first())
        val keys = library.entries(p).first().map { it.sortKey }
        assertEquals(keys.sorted(), keys)
        assertTrue(keys.zipWithNext().all { (x, y) -> y - x > 1e-9 })
    }

    @Test
    fun removeEntriesRemovesOnlyThoseEntries() = runBlocking {
        val p = library.createPlaylist("P")
        library.addTracks(p, listOf(track("a"), track("b")))
        library.addTracks(p, listOf(track("a")), force = true)
        val firstA = library.entries(p).first().first().entryId
        library.removeEntries(listOf(firstA))
        assertEquals(listOf("b", "a"), order(p))
    }

    @Test
    fun deletePlaylistRemovesItsEntries() = runBlocking {
        val p = library.createPlaylist("P")
        library.addTracks(p, listOf(track("a")))
        library.delete(p)
        assertTrue(library.playlists().first().isEmpty())
        assertTrue(library.entries(p).first().isEmpty())
    }

    @Test
    fun renameChangesName() = runBlocking {
        val p = library.createPlaylist("P")
        library.rename(p, "Nueva")
        assertEquals("Nueva", library.playlists().first().single().name)
    }
}
