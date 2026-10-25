/**
 * Vegas desktop: the play queue survives restarts (queue.json), like the persistent queue on Android.
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.desktop.playback

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@Serializable
data class SavedQueue(
    val queue: List<Track>,
    val original: List<Track>? = null,
    val index: Int,
    val positionMs: Long,
    val shuffle: Boolean,
    val repeat: RepeatMode,
    val origin: QueueOrigin? = null,
)

class QueueStore(private val file: Path) {
    private val json = Json { ignoreUnknownKeys = true }

    fun save(saved: SavedQueue) {
        runCatching {
            Files.createDirectories(file.toAbsolutePath().parent)
            val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.writeString(tmp, json.encodeToString(SavedQueue.serializer(), saved))
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    fun load(): SavedQueue? =
        runCatching { json.decodeFromString(SavedQueue.serializer(), Files.readString(file)) }
            .getOrNull()
            ?.takeIf { it.queue.isNotEmpty() && it.index in it.queue.indices }
}
