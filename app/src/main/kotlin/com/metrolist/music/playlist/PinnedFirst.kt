/**
 * Vegas: pinned playlists stay on top of the mixed library view too, whatever the sort order.
 * Licensed under GPL-3.0
 */

package com.metrolist.music.playlist

import com.metrolist.music.db.entities.LocalItem
import com.metrolist.music.db.entities.Playlist

fun <T : LocalItem> pinnedFirst(items: List<T>): List<T> =
    items.sortedByDescending { (it as? Playlist)?.playlist?.pinned == true }
