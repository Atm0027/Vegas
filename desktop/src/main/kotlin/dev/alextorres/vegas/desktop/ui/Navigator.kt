/**
 * Vegas desktop: a back stack per rail destination, like the phone's bottom navigation.
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.desktop.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface Screen {
    data object Home : Screen

    data object Search : Screen

    data object Library : Screen

    data object Settings : Screen

    data class Album(val browseId: String) : Screen

    data class Artist(val browseId: String) : Screen

    data class OnlinePlaylist(val id: String) : Screen

    data class LocalPlaylist(val id: String) : Screen
}

class Navigator {
    private val stack = ArrayDeque<Screen>(listOf(Screen.Home))
    private val _current = MutableStateFlow<Screen>(Screen.Home)
    private val _destination = MutableStateFlow(Destination.HOME)

    val current: StateFlow<Screen> = _current.asStateFlow()
    val destination: StateFlow<Destination> = _destination.asStateFlow()

    fun open(screen: Screen) {
        if (stack.last() == screen) return
        stack.addLast(screen)
        _current.value = screen
    }

    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeLast()
        _current.value = stack.last()
        return true
    }

    fun select(destination: Destination) {
        stack.clear()
        stack.addLast(destination.root())
        _destination.value = destination
        _current.value = stack.last()
    }

    private fun Destination.root(): Screen =
        when (this) {
            Destination.HOME -> Screen.Home
            Destination.SEARCH -> Screen.Search
            Destination.LIBRARY -> Screen.Library
            Destination.SETTINGS -> Screen.Settings
        }
}
