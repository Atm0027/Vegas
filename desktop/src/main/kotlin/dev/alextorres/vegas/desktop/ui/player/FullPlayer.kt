/**
 * Vegas desktop: the full player — large artwork and controls on the left like the phone's player,
 * the reorderable queue on the right where a wide window has room for it.
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.desktop.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.alextorres.vegas.desktop.playback.PlayerUiState
import dev.alextorres.vegas.desktop.playback.RepeatMode
import dev.alextorres.vegas.desktop.playback.Track
import dev.alextorres.vegas.desktop.ui.Artwork
import dev.alextorres.vegas.desktop.ui.Strings
import dev.alextorres.vegas.desktop.ui.formatTime
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

class PlayerActions(
    val togglePlay: () -> Unit,
    val next: () -> Unit,
    val previous: () -> Unit,
    val seekTo: (Long) -> Unit,
    val setShuffle: (Boolean) -> Unit,
    val cycleRepeat: () -> Unit,
    val playAt: (Int) -> Unit,
    val removeFromQueue: (Int) -> Unit,
    val moveInQueue: (Int, Int) -> Unit,
)

@Composable
fun FullPlayer(state: PlayerUiState, actions: PlayerActions, onClose: () -> Unit) {
    val track = state.current ?: return
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize().padding(32.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(
                Modifier.weight(1f).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(Modifier.fillMaxWidth()) {
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.ExpandMore, Strings.CLOSE_PLAYER) }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Artwork(
                        track.thumbnailUrl,
                        Modifier.fillMaxHeight().aspectRatio(1f).widthIn(max = 520.dp),
                        RoundedCornerShape(16.dp),
                        px = 1080,
                    )
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    track.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Text(
                    track.artists,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(16.dp))
                SeekBar(state.positionMs, state.durationMs, actions.seekTo)
                Controls(state, actions)
            }
            QueueList(state, actions, Modifier.widthIn(min = 320.dp, max = 460.dp).fillMaxHeight())
        }
    }
}

@Composable
private fun SeekBar(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val fraction = dragging ?: if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
    Column(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
        Slider(
            value = fraction.coerceIn(0f, 1f),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { onSeek((it * durationMs).toLong()) }
                dragging = null
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val shown = dragging?.let { (it * durationMs).toLong() } ?: positionMs
            Text(formatTime(shown), style = MaterialTheme.typography.labelMedium)
            Text(formatTime(durationMs), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun Controls(state: PlayerUiState, actions: PlayerActions) {
    val active = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        IconButton(onClick = { actions.setShuffle(!state.shuffle) }) {
            Icon(Icons.Rounded.Shuffle, Strings.SHUFFLE, tint = if (state.shuffle) active else idle)
        }
        IconButton(onClick = actions.previous) { Icon(Icons.Rounded.SkipPrevious, Strings.PREVIOUS, Modifier.size(32.dp)) }
        // The phone's player uses a wide pill for play/pause.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(width = 96.dp, height = 56.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(28.dp))
                .clickable(onClick = actions.togglePlay),
        ) {
            Icon(
                if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (state.isPlaying) Strings.PAUSE else Strings.PLAY,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(32.dp),
            )
        }
        IconButton(onClick = actions.next) { Icon(Icons.Rounded.SkipNext, Strings.NEXT, Modifier.size(32.dp)) }
        IconButton(onClick = actions.cycleRepeat) {
            Icon(
                if (state.repeat == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                Strings.REPEAT,
                tint = if (state.repeat == RepeatMode.OFF) idle else active,
            )
        }
    }
}

@Composable
private fun QueueList(state: PlayerUiState, actions: PlayerActions, modifier: Modifier) {
    val items = remember(state.queue) { state.queue.withKeys() }
    var local by remember(items) { mutableStateOf(items) }
    var dragFrom by remember { mutableStateOf<Int?>(null) }
    var dragTo by remember { mutableStateOf<Int?>(null) }
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        local = local.toMutableList().apply { add(to.index, removeAt(from.index)) }
        if (dragFrom == null) dragFrom = from.index
        dragTo = to.index
    }
    LaunchedEffect(reorder.isAnyItemDragging) {
        if (!reorder.isAnyItemDragging) {
            val from = dragFrom
            val to = dragTo
            if (from != null && to != null && from != to) actions.moveInQueue(from, to)
            dragFrom = null
            dragTo = null
        }
    }
    LaunchedEffect(state.index) { if (state.index >= 0) listState.animateScrollToItem(state.index) }

    Column(modifier) {
        Text(Strings.QUEUE, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 12.dp))
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(local, key = { _, item -> item.first }) { index, (key, track) ->
                ReorderableItem(reorder, key = key) {
                    QueueRow(
                        track = track,
                        current = key == items.getOrNull(state.index)?.first,
                        onClick = { actions.playAt(index) },
                        onRemove = { actions.removeFromQueue(index) },
                        handle = Modifier.draggableHandle(),
                    )
                }
            }
        }
    }
}

/** Stable keys even when the same song is queued twice. */
private fun List<Track>.withKeys(): List<Pair<String, Track>> {
    val seen = HashMap<String, Int>()
    return map { t ->
        val n = seen.merge(t.id, 1, Int::plus)!!
        "${t.id}#$n" to t
    }
}

@Composable
private fun QueueRow(track: Track, current: Boolean, onClick: () -> Unit, onRemove: () -> Unit, handle: Modifier) {
    Surface(
        color = if (current) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(onClick = onClick).padding(8.dp),
        ) {
            Artwork(track.thumbnailUrl, Modifier.size(44.dp), px = 120)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(track.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    track.artists,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!current) {
                IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, Strings.REMOVE_FROM_QUEUE) }
            }
            Icon(Icons.Rounded.DragHandle, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = handle.padding(8.dp))
        }
    }
}
