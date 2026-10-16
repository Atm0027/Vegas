/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import com.metrolist.music.LocalDatabase
import com.metrolist.music.R
import com.metrolist.music.db.entities.PlaylistEntity
import java.time.LocalDateTime

@Composable
fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    initialTextFieldValue: String? = null,
    onPlaylistCreated: ((String) -> Unit)? = null,
) {
    val database = LocalDatabase.current

    TextFieldDialog(
        icon = { Icon(painter = painterResource(R.drawable.add), contentDescription = null) },
        title = { Text(text = stringResource(R.string.create_playlist)) },
        initialTextFieldValue = TextFieldValue(initialTextFieldValue ?: ""),
        onDismiss = onDismiss,
        onDone = { playlistName ->
            // The dialog leaves composition right after onDone, so the insert must not depend on its scope.
            val playlist =
                PlaylistEntity(
                    name = playlistName,
                    bookmarkedAt = LocalDateTime.now(),
                    isEditable = true,
                )
            database.query { insert(playlist) }
            onPlaylistCreated?.invoke(playlist.id)
        },
    )
}
