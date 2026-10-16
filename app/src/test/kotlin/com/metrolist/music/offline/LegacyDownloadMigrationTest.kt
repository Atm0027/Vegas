package com.metrolist.music.offline

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.metrolist.music.constants.OfflineMigrationDoneKey
import com.metrolist.music.db.InternalDatabase
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.utils.dataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LegacyDownloadMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun mp4AndWebmHeaders_areRecognised() {
        val mp4 = byteArrayOf(0, 0, 0, 0x20, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())
        val webm = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0, 0, 0, 0)
        assertEquals("audio/mp4", sniffMime(mp4))
        assertEquals("audio/webm", sniffMime(webm))
        assertEquals("audio/mp4", sniffMime(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun emptyLegacyCache_migratesNothing_andIsMarkedDone() = runBlocking {
        val internal = Room.inMemoryDatabaseBuilder(context, InternalDatabase::class.java).allowMainThreadQueries().build()
        val database = MusicDatabase(internal)
        val provider = StandaloneDatabaseProvider(context)
        val cache = SimpleCache(File(context.cacheDir, "legacy-${System.nanoTime()}"), NoOpCacheEvictor(), provider)
        val store = OfflineStore(context)
        val migration = LegacyDownloadMigration(
            context = context,
            database = database,
            databaseProvider = provider,
            downloadCache = cache,
            downloader = SongDownloader(context, database, store),
            queue = DownloadQueue(context),
        )

        assertEquals(LegacyDownloadMigration.MigrationReport(copied = 0, requeued = 0), migration.run())
        assertEquals(true, context.dataStore.data.first()[OfflineMigrationDoneKey])
        cache.release()
        internal.close()
    }

    @Test
    fun completeSongThatFailedToCopy_keepsItsCachedAudio_andMigrationRunsAgain() {
        val plan = LegacyDownloadMigration.cleanup(
            mapOf(
                "a" to LegacyDownloadMigration.Copy.COPIED,
                "b" to LegacyDownloadMigration.Copy.INCOMPLETE,
                "c" to LegacyDownloadMigration.Copy.FAILED,
            ),
        )
        assertEquals(setOf("a", "b"), plan.remove)
        assertEquals(false, plan.done)
    }
}
