/**
 * Vegas: in-memory edit of a local playlist. Nothing touches the database until [commit], which
 * yields dense positions 0..n-1 for the kept rows, so one transaction can apply the whole edit.
 * Licensed under GPL-3.0
 */

package com.metrolist.music.playlist

import com.metrolist.music.db.entities.PlaylistSongMap

class PlaylistEditSession(
    initialMapIds: List<Int>,
    private val initialName: String,
    private val initialThumbnailUrl: String?,
) {
    private val initialOrder = initialMapIds.toList()
    private val all = initialMapIds.toMutableList()
    private val removed = linkedSetOf<Int>()

    var name: String = initialName
        private set
    var thumbnailUrl: String? = initialThumbnailUrl
        private set

    /** Current order without the songs marked for removal. */
    val order: List<Int>
        get() = all.filter { it !in removed }

    val removedIds: Set<Int>
        get() = removed

    /** Moves within [order] (indices of the visible list). */
    fun move(from: Int, to: Int) {
        val visible = order
        if (from !in visible.indices || to !in visible.indices || from == to) return
        val moving = visible[from]
        val target = visible[to]
        all.remove(moving)
        val targetIndex = all.indexOf(target)
        all.add(if (from < to) targetIndex + 1 else targetIndex, moving)
    }

    fun toggleRemove(mapId: Int) {
        if (!removed.remove(mapId)) removed.add(mapId)
    }

    fun rename(newName: String) {
        name = newName
    }

    fun setThumbnail(url: String?) {
        thumbnailUrl = url
    }

    fun commit(): EditResult {
        val kept = order
        return EditResult(
            positions = kept.withIndex().associate { (index, id) -> id to index },
            removed = removed.toSet(),
            name = name,
            thumbnailUrl = thumbnailUrl,
            changed =
                removed.isNotEmpty() ||
                    kept != initialOrder ||
                    name != initialName ||
                    thumbnailUrl != initialThumbnailUrl,
        )
    }
}

data class EditResult(
    val positions: Map<Int, Int>,
    val removed: Set<Int>,
    val name: String,
    val thumbnailUrl: String?,
    val changed: Boolean,
)

data class PlaylistSnapshot(
    val playlistId: String,
    val name: String,
    val thumbnailUrl: String?,
    val maps: List<PlaylistSongMap>,
)
