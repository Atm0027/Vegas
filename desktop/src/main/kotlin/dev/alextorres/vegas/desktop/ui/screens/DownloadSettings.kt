/**
 * Vegas desktop: «Descargas» in Settings — folder, space used, what is downloading and a rescan.
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.desktop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.alextorres.vegas.desktop.ui.Strings
import kotlinx.coroutines.launch
import javax.swing.JFileChooser

@Composable
fun DownloadSettings() {
    val deps = LocalVegas.current
    val scope = rememberCoroutineScope()
    val prefs by deps.settings.flow.collectAsState()
    val downloads by deps.offline.downloads.collectAsState()
    val state by deps.downloads.state.collectAsState()
    val folder = remember(prefs.downloadDir) { deps.offline.folder() }

    Text(Strings.DOWNLOADS, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(Strings.DOWNLOAD_FOLDER, style = MaterialTheme.typography.labelLarge)
            Text(folder.toString(), style = MaterialTheme.typography.bodyLarge)
            Text(
                Strings.downloadStats(downloads.size, downloads.sumOf { it.size }),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            val activity = Strings.downloadActivity(state.progress.size, state.failed.size)
            if (activity.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Text(activity, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    if (state.failed.isNotEmpty()) TextButton(onClick = deps.downloads::retryFailed) { Text(Strings.RETRY_DOWNLOADS) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                OutlinedButton(onClick = {
                    val chooser = JFileChooser(folder.toFile().takeIf { it.isDirectory } ?: folder.parent?.toFile()).apply {
                        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                        dialogTitle = Strings.CHANGE_FOLDER
                    }
                    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                        val dir = chooser.selectedFile.toPath().toAbsolutePath().toString()
                        deps.settings.update { it.copy(downloadDir = dir) }
                        scope.launch { deps.controller.notify(Strings.scanResult(deps.offline.scan())) }
                    }
                }) { Text(Strings.CHANGE_FOLDER) }
                OutlinedButton(onClick = {
                    scope.launch { deps.controller.notify(Strings.scanResult(deps.offline.scan())) }
                }) { Text(Strings.SCAN_FOLDER) }
            }
            Text(
                Strings.CHANGE_FOLDER_NOTE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
