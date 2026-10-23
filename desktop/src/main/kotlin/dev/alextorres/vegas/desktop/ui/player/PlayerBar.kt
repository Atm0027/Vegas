/**
 * Vegas desktop: the bottom player, styled like the phone's mini player (pill, round artwork with
 * a progress ring), with the transport controls and volume a wide window has room for.
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.desktop.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.alextorres.vegas.desktop.playback.PlayerUiState
import dev.alextorres.vegas.desktop.ui.Artwork
import dev.alextorres.vegas.desktop.ui.Strings
import dev.alextorres.vegas.desktop.ui.formatTime

@Composable
fun PlayerBar(
    state: PlayerUiState,
    volume: Float,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onVolume: (Float) -> Unit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = state.current ?: return
    val outline = MaterialTheme.colorScheme.outline
    val shape = RoundedCornerShape(36.dp)
    Box(
        modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, outline.copy(alpha = 0.3f), shape)
            .clickable(onClick = onExpand),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        ) {
            ProgressArtwork(
                url = track.thumbnailUrl,
                progress = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f,
                isPlaying = state.isPlaying,
                onClick = onTogglePlay,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    track.artists,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                "${formatTime(state.positionMs)} / ${formatTime(state.durationMs)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrevious) { Icon(Icons.Rounded.SkipPrevious, Strings.PREVIOUS) }
                IconButton(onClick = onTogglePlay) {
                    Icon(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.isPlaying) Strings.PAUSE else Strings.PLAY)
                }
                IconButton(onClick = onNext) { Icon(Icons.Rounded.SkipNext, Strings.NEXT) }
            }
            Spacer(Modifier.width(16.dp))
            Icon(
                if (volume == 0f) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                Strings.VOLUME,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Slider(value = volume, onValueChange = onVolume, modifier = Modifier.width(120.dp))
            IconButton(onClick = onExpand) { Icon(Icons.Rounded.ExpandLess, Strings.QUEUE) }
        }
    }
}

/** Round artwork with a progress ring; click toggles playback, as on the phone. */
@Composable
private fun ProgressArtwork(url: String?, progress: Float, isPlaying: Boolean, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(52.dp)
            .drawWithContent {
                drawContent()
                val stroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                val d = size.minDimension
                val topLeft = Offset((size.width - d) / 2, (size.height - d) / 2)
                drawArc(track, 0f, 360f, false, topLeft, Size(d, d), style = stroke)
                drawArc(primary, -90f, 360f * progress.coerceIn(0f, 1f), false, topLeft, Size(d, d), style = stroke)
            },
    ) {
        Box(Modifier.size(42.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Artwork(url, Modifier.matchParentSize(), CircleShape, px = 120)
            if (!isPlaying) {
                Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.4f)))
                Icon(Icons.Rounded.PlayArrow, Strings.PLAY, tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
    }
}
