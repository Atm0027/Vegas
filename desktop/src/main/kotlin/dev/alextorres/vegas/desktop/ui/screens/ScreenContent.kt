/**
 * Vegas desktop: which composable draws each screen.
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.desktop.ui.screens

import androidx.compose.runtime.Composable
import dev.alextorres.vegas.desktop.ui.Screen

@Composable
fun ScreenContent(screen: Screen) {
    when (screen) {
        Screen.Home -> HomeScreen()
        Screen.Search -> SearchScreen()
        is Screen.Album -> AlbumScreen(screen.browseId)
        is Screen.Artist -> ArtistScreen(screen.browseId)
        is Screen.OnlinePlaylist -> OnlinePlaylistScreen(screen.id)
        Screen.Library -> LibraryScreen()
        Screen.Settings -> SettingsScreen()
        is Screen.LocalPlaylist -> LocalPlaylistScreen(screen.id)
    }
}
