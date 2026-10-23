/**
 * Vegas desktop: album, artist and YouTube playlist pages.
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.desktop.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.filterExplicit
import com.metrolist.innertube.models.filterPodcasts
import dev.alextorres.vegas.desktop.playback.toTrack
import dev.alextorres.vegas.desktop.ui.Strings

@Composable
fun AlbumScreen(browseId: String) {
    val deps = LocalVegas.current
    val (load, retry) = rememberLoad(browseId) { YouTube.album(browseId) }
    LoadContent(load, retry) { page ->
        val tracks = page.songs.map { it.toTrack().copy(album = page.album.title, thumbnailUrl = it.thumbnail.ifBlank { page.album.thumbnail }) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp)) {
            item {
                PageHeader(
                    thumbnail = page.album.thumbnail,
                    title = page.album.title,
                    subtitle = listOfNotNull(page.album.artists?.joinToString(", ") { it.name }, page.album.year?.toString()).joinToString(" • "),
                    tracks = tracks,
                    actions = { OutlinedButton(onClick = { deps.addToPlaylist(tracks) }) { Text(Strings.ADD_TO_PLAYLIST) } },
                )
            }
            itemsIndexed(tracks) { i, t -> SongRow(t, onPlay = { deps.controller.playQueue(tracks, i) }) }
        }
    }
}

@Composable
fun ArtistScreen(browseId: String) {
    val deps = LocalVegas.current
    val prefs by deps.settings.flow.collectAsState()
    val (load, retry) = rememberLoad(browseId) { YouTube.artist(browseId) }
    LoadContent(load, retry) { page ->
        val sections = page.sections.map { it.title to it.items.filterPodcasts().filterExplicit(prefs.hideExplicit) }.filter { it.second.isNotEmpty() }
        val topSongs = sections.firstOrNull { (_, items) -> items.all { it is SongItem } }
        val tracks = topSongs?.second?.filterIsInstance<SongItem>().orEmpty().map { it.toTrack() }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp)) {
            item { PageHeader(page.artist.thumbnail, page.artist.title, page.monthlyListenerCount ?: page.subscriberCountText, tracks, round = true) }
            sections.forEach { (title, items) ->
                if (items.all { it is SongItem }) {
                    item { SectionTitle(title) }
                    val songTracks = items.filterIsInstance<SongItem>().map { it.toTrack() }
                    itemsIndexed(songTracks) { i, t -> SongRow(t, onPlay = { deps.controller.playQueue(songTracks, i) }) }
                } else {
                    item { ItemRow(title, items) }
                }
            }
        }
    }
}

@Composable
fun OnlinePlaylistScreen(id: String) {
    val deps = LocalVegas.current
    val prefs by deps.settings.flow.collectAsState()
    val (load, retry) = rememberLoad(id) { YouTube.playlist(id) }
    LoadContent(load, retry) { page ->
        val tracks = page.songs.filterExplicit(prefs.hideExplicit).map { it.toTrack() }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp)) {
            item {
                PageHeader(
                    thumbnail = page.playlist.thumbnail,
                    title = page.playlist.title,
                    subtitle = page.playlist.author?.name,
                    tracks = tracks,
                    actions = { OutlinedButton(onClick = { deps.addToPlaylist(tracks) }) { Text(Strings.ADD_TO_PLAYLIST) } },
                )
            }
            itemsIndexed(tracks) { i, t -> SongRow(t, onPlay = { deps.controller.playQueue(tracks, i) }) }
        }
    }
}
