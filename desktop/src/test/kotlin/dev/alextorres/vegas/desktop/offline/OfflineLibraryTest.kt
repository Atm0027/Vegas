package dev.alextorres.vegas.desktop.offline

import dev.alextorres.vegas.desktop.db.DownloadEntity
import dev.alextorres.vegas.desktop.db.Library
import dev.alextorres.vegas.desktop.db.openInMemoryDatabase
import dev.alextorres.vegas.desktop.playback.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class OfflineLibraryTest {
    private val db = openInMemoryDatabase()
    private val library = Library(db)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dir: Path = Files.createTempDirectory("vegas-offline")
    private val removed = mutableListOf<Path>()
    private val offline = OfflineLibrary(db, scope, folder = { dir }, remove = { removed.add(it); Files.deleteIfExists(it) })

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        dir.toFile().deleteRecursively()
    }

    private fun track(id: String) = Track(id, "Song $id", "Artist", null, null, 180_000)

    private suspend fun pendingIds() = offline.pending().first().map { it.id }

    private suspend fun downloaded(id: String): Path {
        val file = dir.resolve(OfflineFileName.build("Artist", "Song $id", id, "m4a"))
        Files.write(file, ByteArray(10))
        offline.record(DownloadEntity(id, file.toString(), 10, "audio/mp4", 0))
        return file
    }

    @Test
    fun markedListAndSingleRequestsArePendingUntilDownloaded() = runBlocking {
        val p = library.createPlaylist("Torre")
        library.addTracks(p, listOf(track("aaaaaaaaaaa"), track("bbbbbbbbbbb")))
        assertEquals(emptyList<String>(), pendingIds())
        offline.markPlaylist(p)
        offline.request(listOf(track("ccccccccccc")))
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc"), pendingIds())
        downloaded("aaaaaaaaaaa")
        downloaded("ccccccccccc")
        assertEquals(listOf("bbbbbbbbbbb"), pendingIds())
    }

    @Test
    fun songAddedToMarkedListBecomesPending() = runBlocking {
        val p = library.createPlaylist("Rock")
        offline.markPlaylist(p)
        library.addTracks(p, listOf(track("aaaaaaaaaaa")))
        assertEquals(listOf("aaaaaaaaaaa"), pendingIds())
    }

    @Test
    fun removingFromListKeepsTheFile() = runBlocking {
        val p = library.createPlaylist("Rock")
        library.addTracks(p, listOf(track("aaaaaaaaaaa")))
        offline.markPlaylist(p)
        val file = downloaded("aaaaaaaaaaa")
        library.removeEntries(library.entries(p).first().map { it.entryId })
        assertTrue(Files.exists(file))
        assertEquals(file, offline.localFile("aaaaaaaaaaa"))
    }

    @Test
    fun unmarkingDeletesOnlyFilesNoOtherMarkedListNeeds() = runBlocking {
        val rock = library.createPlaylist("Rock")
        val torre = library.createPlaylist("Torre")
        library.addTracks(rock, listOf(track("aaaaaaaaaaa"), track("bbbbbbbbbbb")))
        library.addTracks(torre, listOf(track("bbbbbbbbbbb")))
        offline.markPlaylist(rock)
        offline.markPlaylist(torre)
        val a = downloaded("aaaaaaaaaaa")
        val b = downloaded("bbbbbbbbbbb")
        offline.unmarkPlaylist(rock, deleteFiles = true)
        assertEquals(listOf(a), removed)
        assertTrue(Files.exists(b))
        assertNull(offline.localFile("aaaaaaaaaaa"))
    }

    @Test
    fun unmarkingWithoutDeletingKeepsFiles() = runBlocking {
        val rock = library.createPlaylist("Rock")
        library.addTracks(rock, listOf(track("aaaaaaaaaaa")))
        offline.markPlaylist(rock)
        downloaded("aaaaaaaaaaa")
        offline.unmarkPlaylist(rock, deleteFiles = false)
        assertTrue(removed.isEmpty())
        assertEquals(emptyList<String>(), db.offline().offlinePlaylistIds().first())
    }

    @Test
    fun deletingASongOfAMarkedListSaysItWillComeBack() = runBlocking {
        val rock = library.createPlaylist("Rock")
        library.addTracks(rock, listOf(track("aaaaaaaaaaa")))
        offline.markPlaylist(rock)
        downloaded("aaaaaaaaaaa")
        assertEquals("Rock", offline.deleteDownload("aaaaaaaaaaa"))
        assertEquals(listOf("aaaaaaaaaaa"), pendingIds())
    }

    @Test
    fun deletingASingleDownloadForgetsIt() = runBlocking {
        offline.request(listOf(track("aaaaaaaaaaa")))
        downloaded("aaaaaaaaaaa")
        assertNull(offline.deleteDownload("aaaaaaaaaaa"))
        assertEquals(emptyList<String>(), pendingIds())
    }

    @Test
    fun missingFileIsNoLongerDownloaded() = runBlocking {
        val file = downloaded("aaaaaaaaaaa")
        Files.delete(file)
        assertNull(offline.localFile("aaaaaaaaaaa"))
        assertTrue(db.offline().downloadRows().isEmpty())
    }

    @Test
    fun scanAdoptsNamedFilesAndDropsMissingOnes() = runBlocking {
        val gone = downloaded("ggggggggggg")
        Files.delete(gone)
        Files.write(dir.resolve("Fito & Fitipaldis - Soldadito marinero [abc123DEF_-].m4a"), ByteArray(5))
        Files.write(dir.resolve("otra cosa.mp3"), ByteArray(5))
        assertEquals(1, offline.scan())
        val rows = db.offline().downloadRows()
        assertEquals(listOf("abc123DEF_-"), rows.map { it.songId })
        val song = db.offline().song("abc123DEF_-")
        assertEquals("Soldadito marinero", song?.title)
        assertEquals("Fito & Fitipaldis", song?.artists)
        // Scanning again finds nothing new.
        assertEquals(0, offline.scan())
    }
}
