package com.metrolist.music.sync

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.constants.AudioQualityKey
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.db.InternalDatabase
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.PlaylistSongMap
import com.metrolist.music.db.entities.SongEntity
import dev.alextorres.vegas.sync.HlcClock
import dev.alextorres.vegas.sync.InMemorySyncStore
import dev.alextorres.vegas.sync.LibraryAdapter
import dev.alextorres.vegas.sync.LibrarySnapshot
import dev.alextorres.vegas.sync.AppliedChanges
import dev.alextorres.vegas.sync.SyncEngine
import dev.alextorres.vegas.sync.SyncRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** A PC library kept in memory: remembers what it receives, like the real PC does. */
private class PcLibrary : LibraryAdapter {
    private val stored = linkedMapOf<String, dev.alextorres.vegas.sync.LocalPlaylist>()
    val lists get() = stored.values.associate { p -> p.name to p.entries.map { it.song.id } }

    override suspend fun snapshot() = LibrarySnapshot(stored.values.toList(), emptyMap())

    override suspend fun apply(changes: AppliedChanges): Map<String, String> {
        changes.deletedPlaylistIds.forEach { stored.remove(it) }
        changes.playlists.forEach { p ->
            stored[p.reg.id] = dev.alextorres.vegas.sync.LocalPlaylist(
                p.reg.id, p.reg.name, p.reg.pinned, p.reg.customOrder,
                p.entries.map { dev.alextorres.vegas.sync.LocalEntry(it.localKey ?: it.entryId, it.song) },
            )
        }
        return changes.playlists.flatMap { p -> p.entries.filter { it.localKey == null }.map { it.entryId to it.entryId } }.toMap()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AndroidSyncTest {
    private lateinit var internal: InternalDatabase
    private lateinit var database: MusicDatabase
    private lateinit var adapter: AndroidLibraryAdapter
    private lateinit var store: AndroidSyncStore
    private val dataStore = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) {
        File.createTempFile("settings", ".preferences_pb").also { it.delete() }
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        internal = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).allowMainThreadQueries().build()
        database = MusicDatabase(internal)
        adapter = AndroidLibraryAdapter(database, dataStore)
        store = AndroidSyncStore(database)
        listOf("a", "b", "c").forEach { database.insert(SongEntity(id = it, title = "Song $it")) }
    }

    @After
    fun tearDown() = internal.close()

    private fun playlist(id: String, name: String, browseId: String? = null, vararg songs: String) {
        database.insert(PlaylistEntity(id = id, name = name, browseId = browseId, bookmarkedAt = java.time.LocalDateTime.now()))
        songs.forEachIndexed { i, s -> database.insert(PlaylistSongMap(playlistId = id, songId = s, position = i)) }
    }

    private fun songsOf(id: String) = runBlocking { database.playlistSongs(id).first().sortedBy { it.map.position }.map { it.song.id } }

    @Test
    fun snapshotSkipsYoutubeListsAndReadsSettings() = runBlocking {
        playlist("LP1", "Rock", null, "a", "b")
        playlist("VL1", "Saved from YouTube", "VLPL123", "c")
        dataStore.edit { it[AudioQualityKey] = "LOW"; it[HideExplicitKey] = true }

        val snapshot = adapter.snapshot()
        assertEquals(listOf("Rock"), snapshot.playlists.map { it.name })
        assertEquals(listOf("a", "b"), snapshot.playlists.single().entries.map { it.song.id })
        assertEquals("LOW", snapshot.settings["audioQuality"])
        assertEquals("true", snapshot.settings["hideExplicit"])
    }

    @Test
    fun receivingCreatesTheListWithNewSongsInOrder() = runBlocking {
        val pc = SyncEngine(InMemorySyncStore(), object : LibraryAdapter {
            override suspend fun snapshot() = LibrarySnapshot(
                listOf(dev.alextorres.vegas.sync.LocalPlaylist("pc-1", "Prueba PC", true, 0, listOf(
                    dev.alextorres.vegas.sync.LocalEntry("r1", dev.alextorres.vegas.sync.SongMeta("new", "Nueva", "Estopa, Fito")),
                    dev.alextorres.vegas.sync.LocalEntry("r2", dev.alextorres.vegas.sync.SongMeta("a", "Song a", "X")),
                ))),
                mapOf("hideExplicit" to "true"),
            )
            override suspend fun apply(changes: AppliedChanges) = emptyMap<String, String>()
        }, HlcClock("pc"))
        val phone = SyncEngine(store, adapter, HlcClock("phone"))

        val response = pc.respond("phone", SyncRequest("phone", phone.prepareOutgoing("pc"), 0))
        phone.receive("pc", response.changes)

        assertEquals(listOf("new", "a"), songsOf("pc-1"))
        val created = database.playlist("pc-1").first()!!.playlist
        assertEquals("Prueba PC", created.name)
        assertTrue(created.pinned)
        assertEquals(listOf("Estopa", "Fito"), database.song("new").first()!!.artists.map { it.name })
        assertTrue(dataStore.data.first()[HideExplicitKey] == true)
        // Nothing changed locally afterwards: the next capture sends nothing back.
        val before = store.changedSince(0).upToSeq
        phone.captureLocal()
        assertEquals(before, store.changedSince(0).upToSeq)
    }

    @Test
    fun editorRewriteIsNotADuplicate() = runBlocking {
        playlist("LP1", "Rock", null, "a", "b", "c")
        val pcLibrary = PcLibrary()
        val pc = SyncEngine(InMemorySyncStore(), pcLibrary, HlcClock("pc"))
        val phone = SyncEngine(store, adapter, HlcClock("phone"))
        suspend fun sync() {
            val out = phone.prepareOutgoing("pc")
            val res = pc.respond("phone", SyncRequest("phone", out, phone.receivedWatermark("pc")))
            phone.markSent("pc", out.upToSeq)
            phone.receive("pc", res.changes)
        }
        sync()
        assertEquals(listOf("a", "b", "c"), pcLibrary.lists["Rock"])

        // The phone's editor saves by deleting and re-inserting the rows.
        database.playlistSongs("LP1").first().forEach { database.deletePlaylistSongMap(it.map.id) }
        listOf("c", "a", "b").forEachIndexed { i, s -> database.insert(PlaylistSongMap(playlistId = "LP1", songId = s, position = i)) }
        sync()

        assertEquals(listOf("c", "a", "b"), pcLibrary.lists["Rock"])
    }

    @Test
    fun deletedRemoteListIsRemovedLocally() = runBlocking {
        playlist("LP1", "Rock", null, "a")
        val bound = adapter.apply(AppliedChanges(emptyList(), listOf("LP1"), emptyMap()))
        assertTrue(bound.isEmpty())
        assertFalse(database.playlist("LP1").first() != null)
    }
}
