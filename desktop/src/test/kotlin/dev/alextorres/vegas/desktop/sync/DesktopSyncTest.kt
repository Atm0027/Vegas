package dev.alextorres.vegas.desktop.sync

import dev.alextorres.vegas.desktop.db.Library
import dev.alextorres.vegas.desktop.db.openInMemoryDatabase
import dev.alextorres.vegas.desktop.playback.Track
import dev.alextorres.vegas.desktop.settings.AudioQualitySetting
import dev.alextorres.vegas.desktop.settings.DesktopSettings
import dev.alextorres.vegas.sync.AppliedChanges
import dev.alextorres.vegas.sync.DesiredEntry
import dev.alextorres.vegas.sync.DesiredPlaylist
import dev.alextorres.vegas.sync.EntryReg
import dev.alextorres.vegas.sync.Hlc
import dev.alextorres.vegas.sync.PlaylistReg
import dev.alextorres.vegas.sync.SongMeta
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DesktopSyncTest {
    private val db = openInMemoryDatabase()
    private val library = Library(db)
    private val settingsFile = Files.createTempFile("settings", ".json")
    private val settings = DesktopSettings(settingsFile)
    private val adapter = DesktopLibraryAdapter(library, settings)
    private val store = DesktopSyncStore(db)

    @After
    fun tearDown() {
        db.close()
        Files.deleteIfExists(settingsFile)
    }

    private fun track(id: String) = Track(id, "Song $id", "Artist", null, null, null)

    private fun meta(id: String) = SongMeta(id, "Song $id", "Artist")

    private val hlc = Hlc(1, 0, "phone")

    @Test
    fun snapshotReadsPlaylistsInOrderAndSyncedSettings() = runBlocking {
        val id = library.createPlaylist("Rock")
        library.addTracks(id, listOf(track("a"), track("b")))
        settings.update { it.copy(audioQuality = AudioQualitySetting.LOW, hideExplicit = true) }

        val snapshot = adapter.snapshot()
        val rock = snapshot.playlists.single()
        assertEquals("Rock", rock.name)
        assertEquals(listOf("a", "b"), rock.entries.map { it.song.id })
        assertEquals("LOW", snapshot.settings["audioQuality"])
        assertEquals("true", snapshot.settings["hideExplicit"])
    }

    @Test
    fun applyCreatesPlaylistAndEntriesWithSongMetadata() = runBlocking {
        val reg = PlaylistReg("LP-1", "Fito & Estopa", pinned = true, customOrder = 2, deleted = false, hlc = hlc)
        val bound = adapter.apply(
            AppliedChanges(
                listOf(DesiredPlaylist(reg, listOf(DesiredEntry("e1", null, meta("x")), DesiredEntry("e2", null, meta("y"))))),
                emptyList(),
                mapOf("pureBlack" to "true"),
            ),
        )
        val summary = library.playlists().first().single()
        assertEquals("LP-1", summary.id)
        assertEquals("Fito & Estopa", summary.name)
        assertTrue(summary.pinned)
        assertEquals(listOf("x", "y"), library.entries("LP-1").first().map { it.song.id })
        assertEquals(mapOf("e1" to "e1", "e2" to "e2"), bound)
        assertTrue(settings.flow.value.pureBlack)
    }

    @Test
    fun applyRemovalAndReorderKeepsExistingRows() = runBlocking {
        val id = library.createPlaylist("Rock")
        library.addTracks(id, listOf(track("a"), track("b"), track("c")))
        val rows = library.entries(id).first()
        val reg = PlaylistReg(id, "Rock", false, 0, false, hlc)
        adapter.apply(
            AppliedChanges(
                listOf(DesiredPlaylist(reg, listOf(DesiredEntry("c", rows[2].entryId, meta("c")), DesiredEntry("a", rows[0].entryId, meta("a"))))),
                emptyList(),
                emptyMap(),
            ),
        )
        val after = library.entries(id).first()
        assertEquals(listOf("c", "a"), after.map { it.song.id })
        assertEquals(listOf(rows[2].entryId, rows[0].entryId), after.map { it.entryId })
    }

    @Test
    fun applyDeletesPlaylists() = runBlocking {
        val id = library.createPlaylist("Rock")
        adapter.apply(AppliedChanges(emptyList(), listOf(id), emptyMap()))
        assertTrue(library.playlists().first().isEmpty())
    }

    @Test
    fun storeKeepsRegistersBindingsAndWatermarks() = runBlocking {
        val entry = EntryReg("e1", "p", meta("x"), 1.5, false, hlc)
        store.transaction {
            store.putEntry(entry, store.nextSeq())
            store.bind("e1", "row-1")
            store.setWatermark("sent:pc", 7)
        }
        assertEquals(entry, store.entries()["e1"])
        assertEquals("e1", store.entryIdFor("row-1"))
        assertEquals("row-1", store.localKeyFor("e1"))
        assertEquals(7L, store.watermark("sent:pc"))
        assertEquals(listOf(entry), store.changedSince(0).entries)
        assertTrue(store.changedSince(1).entries.isEmpty())
        store.unbind("e1")
        assertNull(store.entryIdFor("row-1"))
    }
}
