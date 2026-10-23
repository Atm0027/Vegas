/**
 * Vegas for Windows.
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.desktop

import androidx.compose.material3.Text
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Vegas",
        state = rememberWindowState(size = DpSize(1280.dp, 800.dp)),
    ) {
        window.minimumSize = java.awt.Dimension(900, 600)
        Text("Vegas")
    }
}
