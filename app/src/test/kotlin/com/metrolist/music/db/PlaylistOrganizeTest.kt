package com.metrolist.music.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.constants.PlaylistSortType
import com.metrolist.music.db.entities.PlaylistEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlaylistOrganizeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun inMemory() =
        Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).allowMainThreadQueries().build()

    private fun playlist(id: String, name: String, day: Int) =
        PlaylistEntity(id = id, name = name, createdAt = LocalDateTime.of(2026, 1, day, 0, 0), bookmarkedAt = LocalDateTime.of(2026, 1, day, 0, 0))

    @Test
    fun pinnedPlaylist_staysFirst_whenSortedByName() =
        runBlocking {
            val db = inMemory()
            db.dao.insert(playlist("a", "Fito & Estopa", 1))
            db.dao.insert(playlist("b", "Rock", 2))
            db.dao.insert(playlist("c", "Torre", 3))
            db.dao.setPlaylistPinned("c", true)

            val asc = db.dao.playlists(PlaylistSortType.NAME, descending = false).first().map { it.playlist.name }
            val desc = db.dao.playlists(PlaylistSortType.NAME, descending = true).first().map { it.playlist.name }

            assertEquals(listOf("Torre", "Fito & Estopa", "Rock"), asc)
            assertEquals(listOf("Torre", "Rock", "Fito & Estopa"), desc)
            db.close()
        }

    @Test
    fun customOrder_isRespected_withPinnedOnTop() =
        runBlocking {
            val db = inMemory()
            db.dao.insert(playlist("a", "Fito & Estopa", 1))
            db.dao.insert(playlist("b", "Rock", 2))
            db.dao.insert(playlist("c", "Torre", 3))
            db.dao.setPlaylistCustomOrders(listOf("b", "c", "a"))

            assertEquals(
                listOf("b", "c", "a"),
                db.dao.playlists(PlaylistSortType.CUSTOM, descending = true).first().map { it.id },
            )

            db.dao.setPlaylistPinned("a", true)
            assertEquals(
                listOf("a", "b", "c"),
                db.dao.playlists(PlaylistSortType.CUSTOM, descending = false).first().map { it.id },
            )
            db.close()
        }

    @Test
    fun migration38To39_keepsPlaylistsUnpinnedWithZeroOrder() =
        runBlocking {
            val name = "migration-38.db"
            val file = context.getDatabasePath(name)
            file.parentFile?.mkdirs()
            file.delete()
            createVersion38(file)

            val db = Room.databaseBuilder(context, InternalDatabase::class.java, name).allowMainThreadQueries().build()
            val playlists = db.dao.playlists(PlaylistSortType.CREATE_DATE, descending = false).first()

            assertEquals(setOf("Torre", "Fito & Estopa", "Rock"), playlists.map { it.playlist.name }.toSet())
            assertEquals(setOf(false), playlists.map { it.playlist.pinned }.toSet())
            assertEquals(setOf(0), playlists.map { it.playlist.customOrder }.toSet())
            db.close()
        }

    /** Builds a version 38 database from the exported schema, as Room's MigrationTestHelper does. */
    private fun createVersion38(file: File) {
        val schema = JSONObject(File("schemas/com.metrolist.music.db.InternalDatabase/38.json").readText()).getJSONObject("database")
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
        listOf("t" to "Torre", "f" to "Fito & Estopa", "r" to "Rock").forEach { (id, title) ->
            sql.execSQL("INSERT INTO playlist (id, name, bookmarkedAt) VALUES (?, ?, 0)", arrayOf(id, title))
        }
        sql.version = 38
        sql.close()
    }
}
