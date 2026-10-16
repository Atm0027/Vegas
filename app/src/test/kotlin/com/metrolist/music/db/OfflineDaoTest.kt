package com.metrolist.music.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.constants.PlaylistSortType
import com.metrolist.music.db.entities.DownloadEntity
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.db.entities.PlaylistSongMap
import com.metrolist.music.db.entities.SongEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OfflineDaoTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).allowMainThreadQueries().build()
    private val dao = db.dao

    @After
    fun close() = db.close()

    private fun song(id: String, liked: Boolean = false) = dao.insert(SongEntity(id = id, title = id, liked = liked))

    private fun playlist(id: String, name: String = id, downloaded: Boolean = false) {
        dao.insert(PlaylistEntity(id = id, name = name, isLocal = true))
        if (downloaded) runBlocking { dao.setPlaylistDownloaded(id, true) }
    }

    private fun add(playlistId: String, songId: String, position: Int = 0) =
        dao.insert(PlaylistSongMap(playlistId = playlistId, songId = songId, position = position))

    private fun download(songId: String) =
        runBlocking { dao.upsertDownload(DownloadEntity(songId, "content://x/$songId", "$songId.m4a", 10, "audio/mp4", LocalDateTime.of(2026, 10, 16, 0, 0))) }

    private fun pending(includeLiked: Boolean = false) = runBlocking { dao.songsToAutoDownload(includeLiked).first().toSet() }

    @Test
    fun trackedPlaylist_queuesMissingSongs_andRequeuesDeletedOnes() {
        listOf("A", "B", "C").forEach(::song)
        playlist("P", downloaded = true)
        add("P", "A", 0)
        add("P", "B", 1)
        download("A")
        assertEquals(setOf("B"), pending())

        add("P", "C", 2)
        assertEquals(setOf("B", "C"), pending())

        download("B")
        runBlocking { dao.deleteDownload("B") }
        assertEquals(setOf("B", "C"), pending())
    }

    @Test
    fun untrackedPlaylist_queuesNothing() {
        song("A")
        playlist("P")
        add("P", "A")
        assertEquals(emptySet<String>(), pending())
    }

    @Test
    fun likedSongs_onlyWhenIncluded() {
        song("D", liked = true)
        assertEquals(emptySet<String>(), pending(includeLiked = false))
        assertEquals(setOf("D"), pending(includeLiked = true))
    }

    @Test
    fun removingSongFromPlaylist_keepsItsDownload() = runBlocking {
        song("B")
        playlist("P", downloaded = true)
        add("P", "B")
        download("B")
        db.openHelper.writableDatabase.execSQL("DELETE FROM playlist_song_map WHERE songId = 'B'")
        assertEquals(emptySet<String>(), pending())
        assertNotNull(dao.downloadEntity("B"))
    }

    @Test
    fun songsOnlyInPlaylist_skipsSongsKeptByAnotherTrackedList() = runBlocking {
        listOf("X", "Y").forEach(::song)
        song("Z", liked = true)
        playlist("P1", downloaded = true)
        playlist("P2", downloaded = true)
        add("P1", "X", 0); add("P1", "Y", 1); add("P1", "Z", 2)
        add("P2", "X", 0)
        listOf("X", "Y", "Z").forEach(::download)

        assertEquals(setOf("Y"), dao.songsOnlyInPlaylist("P1", includeLiked = true).toSet())
        assertEquals(setOf("Y", "Z"), dao.songsOnlyInPlaylist("P1", includeLiked = false).toSet())
    }

    @Test
    fun songsOnlyInPlaylist_ignoresUntrackedOtherPlaylists() = runBlocking {
        song("X")
        playlist("P1", downloaded = true)
        playlist("P2")
        add("P1", "X"); add("P2", "X")
        download("X")
        assertEquals(listOf("X"), dao.songsOnlyInPlaylist("P1", includeLiked = false))
    }

    @Test
    fun trackedPlaylistNames_listsOnlyMarkedPlaylists() = runBlocking {
        song("A")
        playlist("P1", name = "Paseo", downloaded = true)
        playlist("P2", name = "Otra")
        add("P1", "A"); add("P2", "A")
        assertEquals(listOf("Paseo"), dao.trackedPlaylistNames("A"))
    }

    @Test
    fun downloadRow_drivesSongDownloadedFlag() = runBlocking {
        song("A")
        download("A")
        assertTrue(dao.getSongByIdBlocking("A")!!.song.isDownloaded)
        assertEquals(setOf("A"), dao.downloadedIds().first())
        dao.deleteDownload("A")
        assertFalse(dao.getSongByIdBlocking("A")!!.song.isDownloaded)
        assertEquals(emptySet<String>(), dao.downloadedIds().first())
    }

    @Test
    fun migration39To40_keepsPlaylistsUntracked() = runBlocking {
        val name = "migration-39.db"
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        file.delete()
        createVersion39(file)

        val migrated = Room.databaseBuilder(context, InternalDatabase::class.java, name).allowMainThreadQueries().build()
        val playlists = migrated.dao.playlists(PlaylistSortType.CREATE_DATE, descending = false).first()
        assertEquals(setOf("Torre"), playlists.map { it.playlist.name }.toSet())
        assertEquals(setOf(false), playlists.map { it.playlist.downloaded }.toSet())
        assertEquals(emptyList<DownloadEntity>(), migrated.dao.allDownloadsOnce())
        migrated.close()
    }

    private fun createVersion39(file: File) {
        val schema = JSONObject(File("schemas/com.metrolist.music.db.InternalDatabase/39.json").readText()).getJSONObject("database")
        val sql = SQLiteDatabase.openOrCreateDatabase(file, null)
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            sql.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            entity.optJSONArray("indices")?.let { indices ->
                for (j in 0 until indices.length()) {
                    sql.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
        }
        schema.optJSONArray("views")?.let { views ->
            for (i in 0 until views.length()) {
                val view = views.getJSONObject(i)
                sql.execSQL(view.getString("createSql").replace("\${VIEW_NAME}", view.getString("viewName")))
            }
        }
        val setup = schema.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) sql.execSQL(setup.getString(i))
        sql.execSQL("INSERT INTO playlist (id, name, bookmarkedAt) VALUES ('t', 'Torre', 0)")
        sql.version = 39
        sql.close()
    }
}
