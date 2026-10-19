/**
 * Vegas: a playlist counts as downloaded when the user marked it (it then stays downloaded as songs
 * are added). Removing the mark asks whether to delete the files too, "no" by default.
 * Licensed under GPL-3.0
 */

package com.metrolist.music.offline

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.offline.Download
import com.metrolist.music.R
import com.metrolist.music.ui.component.DefaultDialog

/** STOPPED until marked; then DOWNLOADING until every song has its file, COMPLETED after. */
fun playlistDownloadState(marked: Boolean, songIds: List<String>, downloads: Map<String, Download>): Int =
    when {
        !marked -> Download.STATE_STOPPED
        songIds.all { downloads[it]?.state == Download.STATE_COMPLETED } -> Download.STATE_COMPLETED
        else -> Download.STATE_DOWNLOADING
    }

@Composable
fun RemovePlaylistDownloadDialog(
    onDismiss: () -> Unit,
    onConfirm: (deleteFiles: Boolean) -> Unit,
) {
    var deleteFiles by remember { mutableStateOf(false) }
    DefaultDialog(
        onDismiss = onDismiss,
        title = { Text(stringResource(R.string.remove_playlist_download)) },
        content = {
            Text(
                text = stringResource(R.string.remove_playlist_download_desc),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 18.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp)) {
                Checkbox(checked = deleteFiles, onCheckedChange = { deleteFiles = it })
                Text(stringResource(R.string.also_delete_files))
            }
        },
        buttons = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
            TextButton(onClick = { onConfirm(deleteFiles) }) { Text(stringResource(android.R.string.ok)) }
        },
    )
}
