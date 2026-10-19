/**
 * Vegas: one downloaded song, stored as a real file (Music/Vegas on Android 10+). A song counts as
 * downloaded while it has a row here and the file at [uri] still exists.
 * Licensed under GPL-3.0
 */

package com.metrolist.music.db.entities

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDateTime

@Immutable
@Entity(tableName = "download")
data class DownloadEntity(
    @PrimaryKey val songId: String,
    val uri: String,
    val fileName: String,
    val size: Long,
    val mimeType: String,
    val addedAt: LocalDateTime,
)
