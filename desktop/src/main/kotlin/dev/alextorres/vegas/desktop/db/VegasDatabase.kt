/**
 * Vegas desktop: the library database, stored in %APPDATA%\Vegas\vegas.db.
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.desktop.db

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import java.nio.file.Files
import java.nio.file.Path

@Database(entities = [SongEntity::class, PlaylistEntity::class, PlaylistEntryEntity::class], version = 1)
abstract class VegasDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao
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
