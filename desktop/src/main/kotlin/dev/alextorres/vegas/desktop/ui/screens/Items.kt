/**
 * Vegas desktop: building blocks shared by the screens — song rows with their menu, item cards,
 * page headers and the load/error/retry pattern.
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.desktop.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.PointerMatcher
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.onClick
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import dev.alextorres.vegas.desktop.db.Library
import dev.alextorres.vegas.desktop.playback.PlayerController
import dev.alextorres.vegas.desktop.playback.Track
import dev.alextorres.vegas.desktop.playback.toTrack
import dev.alextorres.vegas.desktop.settings.DesktopSettings
import dev.alextorres.vegas.desktop.ui.Artwork
import dev.alextorres.vegas.desktop.ui.Navigator
import dev.alextorres.vegas.desktop.ui.Screen
import dev.alextorres.vegas.desktop.ui.Strings
import dev.alextorres.vegas.desktop.ui.formatTime

/** Everything a screen needs, provided once from Main. */
class VegasDeps(
    val controller: PlayerController,
    val library: Library,
    val navigator: Navigator,
    val settings: DesktopSettings,
    /** Opens «Añadir a una lista…» for these tracks. */
    val addToPlaylist: (List<Track>) -> Unit,
    /** Home-network sync with the phone; null if the server could not start. */
    val sync: dev.alextorres.vegas.desktop.sync.SyncService? = null,
)

val LocalVegas = compositionLocalOf<VegasDeps> { error("VegasDeps not provided") }

sealed interface Load<out T> {
    data object Loading : Load<Nothing>

    data class Done<T>(val value: T) : Load<T>

    data object Failed : Load<Nothing>
}

/** Runs [block] for [key]; the returned retry() runs it again. */
@Composable
fun <T> rememberLoad(key: Any?, block: suspend () -> Result<T>): Pair<Load<T>, () -> Unit> {
    var attempt by remember(key) { mutableIntStateOf(0) }
    var state by remember(key) { mutableStateOf<Load<T>>(Load.Loading) }
    LaunchedEffect(key, attempt) {
        state = Load.Loading
        state = block().fold({ Load.Done(it) }, { Load.Failed })
    }
    return state to { attempt++ }
}

@Composable
fun <T> LoadContent(load: Load<T>, retry: () -> Unit, content: @Composable (T) -> Unit) {
    when (load) {
        Load.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        Load.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(Strings.LOAD_ERROR)
                Spacer(Modifier.height(12.dp))
                Button(onClick = retry) { Text(Strings.RETRY) }
            }
        }
        is Load.Done -> content(load.value)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = modifier.padding(vertical = 12.dp))
}

/** One song in a list: click plays, right click or ⋮ opens the menu. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    track: Track,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    extraMenu: (@Composable (close: () -> Unit) -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val deps = LocalVegas.current
    var menu by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .onClick(matcher = PointerMatcher.mouse(PointerButton.Secondary)) { menu = true }
            .clickable(onClick = onPlay)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Artwork(track.thumbnailUrl, Modifier.size(48.dp), px = 120)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(track.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(track.artists.takeIf { it.isNotBlank() }, track.album).joinToString(" • "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        track.durationMs?.let {
            Text(formatTime(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, null) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(Strings.PLAY_NEXT) }, onClick = { deps.controller.playNext(track); menu = false })
                DropdownMenuItem(text = { Text(Strings.ADD_TO_QUEUE) }, onClick = { deps.controller.addToQueue(track); menu = false })
                DropdownMenuItem(text = { Text(Strings.ADD_TO_PLAYLIST) }, onClick = { deps.addToPlaylist(listOf(track)); menu = false })
                extraMenu?.invoke { menu = false }
            }
        }
    }
}

/** Square card for albums, playlists and songs; round for artists. */
@Composable
fun ItemCard(item: YTItem, onClick: () -> Unit) {
    val round = item is ArtistItem
    Column(Modifier.width(168.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(8.dp)) {
        Artwork(item.thumbnail, Modifier.size(152.dp), if (round) CircleShape else RoundedCornerShape(12.dp), px = 320)
        Spacer(Modifier.height(8.dp))
        Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        subtitleOf(item)?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

fun subtitleOf(item: YTItem): String? =
    when (item) {
        is SongItem -> item.artists.joinToString(", ") { it.name }
        is AlbumItem -> listOfNotNull(item.artists?.joinToString(", ") { it.name }, item.year?.toString()).joinToString(" • ")
        is PlaylistItem -> item.author?.name
        else -> null
    }?.takeIf { it.isNotBlank() }

/** What clicking an item does everywhere: songs play the visible songs from that one, the rest open their page. */
fun VegasDeps.open(item: YTItem, siblings: List<YTItem>) {
    when (item) {
        is SongItem -> {
            val songs = siblings.filterIsInstance<SongItem>()
            controller.playQueue(songs.map { it.toTrack() }, songs.indexOf(item).coerceAtLeast(0))
        }
        is AlbumItem -> navigator.open(Screen.Album(item.browseId))
        is ArtistItem -> navigator.open(Screen.Artist(item.id))
        is PlaylistItem -> navigator.open(Screen.OnlinePlaylist(item.id))
        else -> Unit
    }
}

@Composable
fun ItemRow(title: String, items: List<YTItem>) {
    val deps = LocalVegas.current
    Column {
        SectionTitle(title)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items(items, key = { it.id }) { item -> ItemCard(item) { deps.open(item, items) } }
        }
    }
}

/** Large artwork, title, subtitle and Play / Shuffle, as on the phone's album and playlist pages. */
@Composable
fun PageHeader(
    thumbnail: String?,
    title: String,
    subtitle: String?,
    tracks: List<Track>,
    round: Boolean = false,
    actions: @Composable () -> Unit = {},
) {
    val deps = LocalVegas.current
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        Artwork(thumbnail, Modifier.size(220.dp), if (round) CircleShape else RoundedCornerShape(16.dp), px = 544)
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { deps.controller.playQueue(tracks, 0) }, enabled = tracks.isNotEmpty()) {
                    Icon(Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text(Strings.PLAY)
                }
                FilledTonalButton(onClick = { deps.controller.playQueue(tracks, tracks.indices.random(), shuffle = true) }, enabled = tracks.isNotEmpty()) {
                    Icon(Icons.Rounded.Shuffle, null)
                    Spacer(Modifier.width(8.dp))
                    Text(Strings.SHUFFLE)
                }
                actions()
            }
        }
    }
}
