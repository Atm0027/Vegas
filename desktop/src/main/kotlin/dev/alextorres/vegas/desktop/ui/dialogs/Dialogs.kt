/**
 * Vegas desktop: «Añadir a una lista…» (several lists at once, duplicate warning like the phone)
 * and a small text dialog for naming lists.
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.desktop.ui.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import dev.alextorres.vegas.desktop.db.Library
import dev.alextorres.vegas.desktop.playback.Track
import dev.alextorres.vegas.desktop.ui.Artwork
import dev.alextorres.vegas.desktop.ui.Strings
import kotlinx.coroutines.launch

@Composable
fun TextInputDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(text, { text = it }, singleLine = true, label = { Text(Strings.PLAYLIST_NAME) }, modifier = Modifier.focusRequester(focus))
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Strings.CANCEL) } },
    )
}

@Composable
fun AddToPlaylistDialog(tracks: List<Track>, library: Library, onDone: (message: String?) -> Unit) {
    val scope = rememberCoroutineScope()
    val playlists by library.playlists().collectAsState(emptyList())
    var selected by remember { mutableStateOf(setOf<String>()) }
    var creating by remember { mutableStateOf(false) }
    var duplicates by remember { mutableStateOf<List<Pair<String, List<Track>>>?>(null) }

    fun names(ids: Collection<String>) = playlists.filter { it.id in ids }.joinToString(", ") { it.name }

    duplicates?.let { pending ->
        AlertDialog(
            onDismissRequest = { onDone(null) },
            title = { Text(Strings.ALREADY_IN_PLAYLIST) },
            text = {
                Text(pending.joinToString("\n") { (id, songs) -> "${names(listOf(id))}: " + songs.joinToString(", ") { it.title } })
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        pending.forEach { (id, songs) -> library.addTracks(id, songs, force = true) }
                        onDone(Strings.added(tracks.size, names(selected)))
                    }
                }) { Text(Strings.ADD_ANYWAY) }
            },
            dismissButton = { TextButton(onClick = { onDone(Strings.added(tracks.size, names(selected))) }) { Text(Strings.SKIP) } },
        )
        return
    }

    if (creating) {
        TextInputDialog(Strings.NEW_PLAYLIST, "", Strings.CREATE, onDismiss = { creating = false }) { name ->
            scope.launch {
                selected = selected + library.createPlaylist(name)
                creating = false
            }
        }
        return
    }

    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(Strings.ADD_TO_PLAYLIST.removeSuffix("…")) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { creating = true }.padding(vertical = 8.dp),
                    ) {
                        Icon(Icons.Rounded.Add, null, Modifier.padding(horizontal = 12.dp))
                        Text(Strings.NEW_PLAYLIST, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                items(playlists, key = { it.id }) { p ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { selected = if (p.id in selected) selected - p.id else selected + p.id }.padding(vertical = 4.dp),
                    ) {
                        Checkbox(p.id in selected, onCheckedChange = null, modifier = Modifier.padding(horizontal = 12.dp))
                        Artwork(p.thumbnailUrl, Modifier.size(40.dp), px = 120)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(p.name, style = MaterialTheme.typography.bodyLarge)
                            Text(Strings.songCount(p.songCount), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = selected.isNotEmpty(), onClick = {
                scope.launch {
                    val dup = selected.mapNotNull { id ->
                        library.addTracks(id, tracks).duplicates.takeIf { it.isNotEmpty() }?.let { id to it }
                    }
                    if (dup.isEmpty()) onDone(Strings.added(tracks.size, names(selected))) else duplicates = dup
                }
            }) { Text(Strings.ADD) }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text(Strings.CANCEL) } },
    )
}
