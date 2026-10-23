/**
 * Vegas desktop: navigation rail, content and the player bar; the full player slides over everything.
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.alextorres.vegas.desktop.playback.PlayerUiState
import dev.alextorres.vegas.desktop.ui.player.FullPlayer
import dev.alextorres.vegas.desktop.ui.player.PlayerActions
import dev.alextorres.vegas.desktop.ui.player.PlayerBar
import kotlinx.coroutines.delay

private const val MESSAGE_DURATION_MS = 4_000L

enum class Destination(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    HOME(Strings.HOME, Icons.Outlined.Home, Icons.Rounded.Home),
    SEARCH(Strings.SEARCH, Icons.Outlined.Search, Icons.Rounded.Search),
    LIBRARY(Strings.LIBRARY, Icons.Outlined.LibraryMusic, Icons.Rounded.LibraryMusic),
    SETTINGS(Strings.SETTINGS, Icons.Outlined.Settings, Icons.Rounded.Settings),
}

@Composable
fun AppShell(
    destination: Destination,
    onDestination: (Destination) -> Unit,
    player: PlayerUiState,
    actions: PlayerActions,
    volume: Float,
    onVolume: (Float) -> Unit,
    fullPlayerOpen: Boolean,
    onFullPlayer: (Boolean) -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surface,
                    header = {
                        Image(painterResource("vegas.png"), Strings.APP_NAME, Modifier.padding(vertical = 16.dp).size(40.dp))
                    },
                ) {
                    Destination.entries.forEach { d ->
                        if (d == Destination.SETTINGS) Spacer(Modifier.weight(1f))
                        NavigationRailItem(
                            selected = d == destination,
                            onClick = { onDestination(d) },
                            icon = { Icon(if (d == destination) d.selectedIcon else d.icon, d.label) },
                            label = { Text(d.label) },
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Box(Modifier.weight(1f)) { content() }
                    if (player.current != null) {
                        PlayerBar(
                            state = player,
                            volume = volume,
                            onTogglePlay = actions.togglePlay,
                            onNext = actions.next,
                            onPrevious = actions.previous,
                            onVolume = onVolume,
                            onExpand = { onFullPlayer(true) },
                            modifier = Modifier.padding(start = 8.dp, end = 16.dp, bottom = 12.dp, top = 4.dp),
                        )
                    }
                }
            }
            AnimatedVisibility(
                visible = fullPlayerOpen && player.current != null,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                FullPlayer(player, actions, onClose = { onFullPlayer(false) })
            }
            player.message?.let { message ->
                LaunchedEffect(message) {
                    delay(MESSAGE_DURATION_MS)
                    onDismissMessage()
                }
                Snackbar(
                    action = { TextButton(onClick = onDismissMessage) { Text("OK") } },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 100.dp),
                ) { Text(message) }
            }
        }
    }
}
