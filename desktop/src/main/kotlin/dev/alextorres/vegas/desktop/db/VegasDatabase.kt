/**
 * Vegas desktop: the library database, stored in %APPDATA%\Vegas\vegas.db.
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.desktop.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import java.nio.file.Files
import java.nio.file.Path

@Database(
    entities = [
        SongEntity::class, PlaylistEntity::class, PlaylistEntryEntity::class,
        SyncPlaylistEntity::class, SyncEntryEntity::class, SyncSettingEntity::class, SyncBindingEntity::class, SyncMetaEntity::class,
        DownloadEntity::class, OfflinePlaylistEntity::class, DownloadRequestEntity::class,
    ],
    version = 3,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class VegasDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao

    abstract fun sync(): SyncDao

    abstract fun offline(): OfflineDao
}

fun openDatabase(file: Path): VegasDatabase {
    Files.createDirectories(file.toAbsolutePath().parent)
    return Room.databaseBuilder<VegasDatabase>(name = file.toAbsolutePath().toString())
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
}

fun openInMemoryDatabase(): VegasDatabase =
    Room.inMemoryDatabaseBuilder<VegasDatabase>()
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
