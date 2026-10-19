package com.metrolist.music.playlist

import com.metrolist.music.db.entities.Playlist
import com.metrolist.music.db.entities.PlaylistEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class PinnedFirstTest {
    private fun list(name: String, pinned: Boolean = false) =
        Playlist(PlaylistEntity(id = name, name = name, pinned = pinned), songCount = 0, songThumbnails = emptyList())

    @Test
    fun pinnedPlaylistsComeFirst_restKeepsItsOrder() {
        val items = listOf(list("a"), list("b", pinned = true), list("c"), list("d", pinned = true))
        assertEquals(listOf("b", "d", "a", "c"), pinnedFirst(items).map { it.id })
    }
}
