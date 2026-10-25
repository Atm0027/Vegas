/**
 * Vegas desktop: the library (own playlists, pinned first, drag to reorder, grid or list) and a
 * playlist's page (play, shuffle, drag to reorder with Ctrl+Z, «Quitar de la lista», download, rename,
 * pin, delete).
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.desktop.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.alextorres.vegas.desktop.db.EntryWithSong
import dev.alextorres.vegas.desktop.db.PlaylistSummary
import dev.alextorres.vegas.desktop.ui.Artwork
import dev.alextorres.vegas.desktop.ui.Screen
import dev.alextorres.vegas.desktop.ui.Strings
import dev.alextorres.vegas.desktop.ui.dialogs.TextInputDialog
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun LibraryScreen() {
    val deps = LocalVegas.current
    val scope = rememberCoroutineScope()
    val playlists by deps.library.playlists().collectAsState(null)
    var grid by rememberSaveable { mutableStateOf(true) }
    var creating by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Strings.PLAYLISTS, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = { grid = !grid }) {
                    Icon(if (grid) Icons.Rounded.ViewList else Icons.Rounded.GridView, if (grid) Strings.LIST else Strings.GRID)
                }
            }
            val list = playlists ?: return@Column
            if (list.isEmpty()) {
                Text(Strings.EMPTY_LIBRARY, modifier = Modifier.padding(vertical = 24.dp))
            } else {
                ReorderablePlaylists(list, grid, onOpen = { deps.navigator.open(Screen.LocalPlaylist(it.id)) }) { ordered ->
                    scope.launch { deps.library.reorderPlaylists(ordered.map { it.id }) }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { creating = true },
            icon = { Icon(Icons.Rounded.Add, null) },
            text = { Text(Strings.NEW_PLAYLIST) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
        )
    }
    if (creating) {
        TextInputDialog(Strings.NEW_PLAYLIST, "", Strings.CREATE, onDismiss = { creating = false }) { name ->
            scope.launch { deps.library.createPlaylist(name) }
            creating = false
        }
    }
}

/** Pinned playlists stay on top: dragging only reorders within the pinned or the unpinned group. */
@Composable
private fun ReorderablePlaylists(
    playlists: List<PlaylistSummary>,
    grid: Boolean,
    onOpen: (PlaylistSummary) -> Unit,
    onReordered: (List<PlaylistSummary>) -> Unit,
) {
    var local by remember(playlists) { mutableStateOf(playlists) }
    var dragged by remember { mutableStateOf(false) }
    val move: (Int, Int) -> Unit = { from, to ->
        if (local[from].pinned == local[to].pinned) {
            local = local.toMutableList().apply { add(to, removeAt(from)) }
            dragged = true
        }
    }
    if (grid) {
        val state = rememberLazyGridState()
        val reorder = rememberReorderableLazyGridState(state) { from, to -> move(from.index, to.index) }
        LaunchedEffect(reorder.isAnyItemDragging) { if (!reorder.isAnyItemDragging && dragged) { dragged = false; onReordered(local) } }
        LazyVerticalGrid(GridCells.Adaptive(176.dp), state = state, contentPadding = PaddingValues(bottom = 96.dp)) {
            items(local, key = { it.id }) { p ->
                ReorderableItem(reorder, key = p.id) {
                    Column(
                        Modifier.clip(RoundedCornerShape(12.dp)).longPressDraggableHandle().clickable { onOpen(p) }.padding(8.dp),
                    ) {
                        Artwork(p.thumbnailUrl, Modifier.size(160.dp), RoundedCornerShape(12.dp), px = 320)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                            if (p.pinned) Icon(Icons.Rounded.PushPin, null, Modifier.size(14.dp).padding(end = 2.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(p.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(Strings.songCount(p.songCount), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    } else {
        val state = rememberLazyListState()
        val reorder = rememberReorderableLazyListState(state) { from, to -> move(from.index, to.index) }
        LaunchedEffect(reorder.isAnyItemDragging) { if (!reorder.isAnyItemDragging && dragged) { dragged = false; onReordered(local) } }
        LazyColumn(state = state, contentPadding = PaddingValues(bottom = 96.dp)) {
            itemsIndexed(local, key = { _, p -> p.id }) { _, p ->
                ReorderableItem(reorder, key = p.id) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onOpen(p) }.padding(8.dp),
                    ) {
                        Artwork(p.thumbnailUrl, Modifier.size(56.dp), px = 160)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(p.name, style = MaterialTheme.typography.bodyLarge)
                            Text(Strings.songCount(p.songCount), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (p.pinned) Icon(Icons.Rounded.PushPin, Strings.PIN, tint = MaterialTheme.colorScheme.primary)
                        Icon(Icons.Rounded.DragHandle, null, Modifier.draggableHandle().padding(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun LocalPlaylistScreen(id: String) {
    val deps = LocalVegas.current
    val scope = rememberCoroutineScope()
    val playlist by deps.library.playlists().collectAsState(null)
    val summary = playlist?.firstOrNull { it.id == id }
    val entries by deps.library.entries(id).collectAsState(null)
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var unmarking by remember { mutableStateOf(false) }
    val offline by deps.offline.offlinePlaylists.collectAsState()
    val downloadedIds by deps.offline.downloadedIds.collectAsState()
    /** Last drag: (entry, index it came from) — Ctrl+Z puts it back. */
    var lastMove by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    if (playlist != null && summary == null) {
        LaunchedEffect(Unit) { deps.navigator.back() }
        return
    }
    val rows = entries ?: return
    val name = summary?.name ?: return
    val tracks = rows.map { it.song.toTrack() }

    var local by remember(rows) { mutableStateOf(rows) }
    var dragFrom by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var dragTo by remember { mutableStateOf<Int?>(null) }
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        // Index 0 is the header; entries start at 1.
        val f = from.index - 1
        val t = to.index - 1
        if (f < 0 || t < 0) return@rememberReorderableLazyListState
        if (dragFrom == null) dragFrom = local[f].entryId to f
        local = local.toMutableList().apply { add(t, removeAt(f)) }
        dragTo = t
    }
    LaunchedEffect(reorder.isAnyItemDragging) {
        if (!reorder.isAnyItemDragging) {
            val from = dragFrom
            val to = dragTo
            if (from != null && to != null && from.second != to) {
                deps.library.moveEntry(id, from.first, to)
                lastMove = from
            }
            dragFrom = null
            dragTo = null
        }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { e ->
                val undo = lastMove
                if (e.type == KeyEventType.KeyDown && e.isCtrlPressed && e.key == Key.Z && undo != null) {
                    scope.launch { deps.library.moveEntry(id, undo.first, undo.second) }
                    lastMove = null
                    true
                } else {
                    false
                }
            },
    ) {
        item {
            PageHeader(rows.firstOrNull()?.song?.thumbnailUrl, name, Strings.songCount(rows.size), tracks) {
                val marked = id in offline
                val songIds = rows.mapTo(HashSet()) { it.song.id }
                val done = songIds.count { it in downloadedIds }
                OutlinedButton(onClick = { if (marked) unmarking = true else scope.launch { deps.offline.markPlaylist(id) } }) {
                    Icon(if (marked && done == songIds.size) Icons.Rounded.DownloadDone else Icons.Rounded.Download, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            !marked -> Strings.DOWNLOAD
                            done < songIds.size -> Strings.downloading(done, songIds.size)
                            else -> Strings.DOWNLOADED
                        },
                    )
                }
                OutlinedButton(onClick = { scope.launch { deps.library.setPinned(id, !summary.pinned) } }) {
                    Text(if (summary.pinned) Strings.UNPIN else Strings.PIN)
                }
                TextButton(onClick = { renaming = true }) { Text(Strings.RENAME) }
                TextButton(onClick = { deleting = true }) { Text(Strings.DELETE, color = MaterialTheme.colorScheme.error) }
            }
            if (rows.isEmpty()) Text(Strings.EMPTY_PLAYLIST, Modifier.padding(vertical = 16.dp))
        }
        itemsIndexed(local, key = { _, e -> e.entryId }) { index, entry ->
            ReorderableItem(reorder, key = entry.entryId) {
                PlaylistEntryRow(
                    entry = entry,
                    onPlay = { deps.controller.playQueue(local.map { it.song.toTrack() }, index) },
                    onRemove = { scope.launch { deps.library.removeEntries(listOf(entry.entryId)) } },
                    handle = Modifier.draggableHandle(),
                )
            }
        }
    }

    if (renaming) {
        TextInputDialog(Strings.RENAME, name, Strings.SAVE, onDismiss = { renaming = false }) { newName ->
            scope.launch { deps.library.rename(id, newName) }
            renaming = false
        }
    }
    if (unmarking) {
        var deleteFiles by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { unmarking = false },
            title = { Text(Strings.removeDownloadTitle(name)) },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { deleteFiles = !deleteFiles }) {
                        Checkbox(deleteFiles, onCheckedChange = null)
                        Text(Strings.DELETE_FILES_TOO, Modifier.padding(start = 8.dp))
                    }
                    Text(
                        Strings.DELETE_FILES_EXPLAIN,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    unmarking = false
                    scope.launch { deps.offline.unmarkPlaylist(id, deleteFiles) }
                }) { Text(Strings.REMOVE_DOWNLOAD) }
            },
            dismissButton = { TextButton(onClick = { unmarking = false }) { Text(Strings.CANCEL) } },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(Strings.deleteConfirm(name)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = false
                    scope.launch { deps.library.delete(id) }
                }) { Text(Strings.DELETE, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(Strings.CANCEL) } },
        )
    }
}

@Composable
private fun PlaylistEntryRow(entry: EntryWithSong, onPlay: () -> Unit, onRemove: () -> Unit, handle: Modifier) {
    SongRow(
        track = entry.song.toTrack(),
        onPlay = onPlay,
        extraMenu = { close ->
            DropdownMenuItem(text = { Text(Strings.REMOVE_FROM_PLAYLIST) }, onClick = { onRemove(); close() })
        },
        trailing = {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Rounded.DragHandle, null, handle.padding(8.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    )
}
