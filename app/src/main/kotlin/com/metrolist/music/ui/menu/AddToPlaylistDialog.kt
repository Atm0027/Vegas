/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.menu

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.metrolist.music.LocalDatabase
import com.metrolist.music.R
import com.metrolist.music.constants.AddToPlaylistPosition
import com.metrolist.music.constants.AddToPlaylistPositionKey
import com.metrolist.music.constants.AddToPlaylistSortDescendingKey
import com.metrolist.music.constants.AddToPlaylistSortTypeKey
import com.metrolist.music.constants.ListThumbnailSize
import com.metrolist.music.constants.PlaylistSortType
import com.metrolist.music.db.DatabaseDao
import com.metrolist.music.db.entities.Playlist
import com.metrolist.music.ui.component.CreatePlaylistDialog
import com.metrolist.music.ui.component.DefaultDialog
import com.metrolist.music.ui.component.ListDialog
import com.metrolist.music.ui.component.ListItem
import com.metrolist.music.ui.component.PlaylistListItem
import com.metrolist.music.ui.component.SortHeader
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.viewmodels.PlaylistsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.platform.LocalContext
import com.metrolist.music.playlist.AddPlan
import com.metrolist.music.playlist.Candidate
import com.metrolist.music.playlist.planPlaylistAdd
import com.metrolist.music.utils.makeTimeString
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.FilterChip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.FilterChipDefaults

@Composable
fun AddToPlaylistDialog(
    isVisible: Boolean,
    initialTextFieldValue: String? = null,
    onGetSong: suspend () -> List<String>, // list of song ids. Songs should be inserted to database in this function.
    onGetSongIds: (suspend () -> List<String>)? = null,
    onDismiss: () -> Unit,
    viewModel: PlaylistsViewModel = hiltViewModel()
) {
    val database = LocalDatabase.current
    val coroutineScope = rememberCoroutineScope()
    val (addToPlaylistPosition) = rememberEnumPreference(
        AddToPlaylistPositionKey,
        AddToPlaylistPosition.BEGINNING,
    )
    val (sortType, onSortTypeChange) = rememberEnumPreference(
        AddToPlaylistSortTypeKey,
        PlaylistSortType.NAME
    )
    val (sortDescending, onSortDescendingChange) = rememberPreference(
        AddToPlaylistSortDescendingKey,
        false
    )
    val playlists by viewModel.allPlaylists.collectAsStateWithLifecycle()
    var showCreatePlaylistDialog by rememberSaveable {
        mutableStateOf(false)
    }

    val context = LocalContext.current
    var songIds by remember {
        mutableStateOf<List<String>?>(null)
    }
    var playlistsContainingSong by remember {
        mutableStateOf<Set<String>>(emptySet())
    }
    val selectedPlaylistIds = remember { mutableStateListOf<String>() }
    var pendingPlans by remember { mutableStateOf<List<Pair<Playlist, AddPlan>>>(emptyList()) }
    // "playlistId|songId" of probable duplicates the user still wants to add
    val acceptedProbables = remember { mutableStateListOf<String>() }
    // Set synchronously on tap so a double tap cannot plan and insert the same songs twice.
    var adding by remember { mutableStateOf(false) }

    fun commitPlans(plans: List<Pair<Playlist, AddPlan>>, accepted: Set<String>) {
        pendingPlans = emptyList()
        adding = true
        coroutineScope.launch(Dispatchers.IO + NonCancellable) {
            val summary =
                plans.map { (target, plan) ->
                    val ids =
                        plan.idsToAdd(
                            accepted.filter { it.startsWith("${target.id}|") }.map { it.substringAfter('|') }.toSet(),
                        )
                    if (ids.isNotEmpty()) {
                        database.addSongsToPlaylist(target, ids.map { it to null }, prepend = addToPlaylistPosition.prepend)
                    }
                    buildString {
                        append(context.getString(R.string.added_to_playlist_summary, ids.size, target.playlist.name))
                        if (plan.alreadyThere.isNotEmpty()) {
                            append(" · ")
                            append(context.getString(R.string.already_in_playlist_count, plan.alreadyThere.size))
                        }
                    }
                }
            withContext(Dispatchers.Main) {
                Toast.makeText(context, summary.joinToString("\n"), Toast.LENGTH_LONG).show()
                selectedPlaylistIds.clear()
                acceptedProbables.clear()
                adding = false
                onDismiss()
            }
        }
    }

    fun addToSelected() {
        val targets = playlists.filter { it.id in selectedPlaylistIds }
        if (targets.isEmpty() || adding) return
        adding = true
        coroutineScope.launch(Dispatchers.IO) {
            val ids = onGetSong()
            val plans = targets.map { it to database.planPlaylistAdd(it.id, ids) }
            withContext(Dispatchers.Main) {
                if (plans.any { it.second.probable.isNotEmpty() }) {
                    acceptedProbables.clear()
                    pendingPlans = plans
                    adding = false
                } else {
                    commitPlans(plans, emptySet())
                }
            }
        }
    }

    LaunchedEffect(isVisible, playlists.isEmpty()) {
        if (!isVisible || playlists.isEmpty()) return@LaunchedEffect
        if (songIds != null) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            songIds = onGetSongIds?.invoke() ?: onGetSong()
        }
    }
    LaunchedEffect(isVisible, songIds, playlists) {
        if (!isVisible) {
            songIds = null
            playlistsContainingSong = emptySet()
            selectedPlaylistIds.clear()
            return@LaunchedEffect
        }
        val ids = songIds ?: return@LaunchedEffect
        withContext(Dispatchers.IO) {
            playlistsContainingSong = playlists
                .filter { database.playlistDuplicatesBatched(it.id, ids).isNotEmpty() }
                .map { it.id }
                .toSet()
        }
    }

    if (isVisible) {
        ListDialog(
            onDismiss = onDismiss,
        ) {
            item {
                val interactionSource = remember { MutableInteractionSource() }
                val isPressed by interactionSource.collectIsPressedAsState()
                val scale by animateFloatAsState(
                    targetValue = if (isPressed) 0.7f else 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    ),
                    label = "buttonScale"
                )
                FilledTonalButton(
                    onClick = { showCreatePlaylistDialog = true},
                    shape = RoundedCornerShape(50),
                    interactionSource = interactionSource,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .graphicsLayer { scaleX = scale; scaleY = scale }
                ) {
                    Icon(
                        painter = painterResource(R.drawable.add),
                        contentDescription = null,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(20.dp)
                    )
                    Text(
                        text = stringResource(R.string.create_playlist),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            if (selectedPlaylistIds.isNotEmpty()) {
                item {
                    FilledTonalButton(
                        onClick = ::addToSelected,
                        enabled = !adding,
                        shape = RoundedCornerShape(50),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.add_to_playlists, selectedPlaylistIds.size),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            if (playlists.isNotEmpty()) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            PlaylistSortType.entries.forEach { type ->
                                val selected = sortType == type
                                FilterChip(
                                    selected = selected,
                                    onClick = { onSortTypeChange(type) },
                                    shape = RoundedCornerShape(50),
                                    border = FilterChipDefaults.filterChipBorder(
                                        enabled = true,
                                        selected = selected,
                                        borderWidth = 0.dp,
                                        selectedBorderWidth = 0.dp,
                                    ),
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    ),
                                    label = {
                                        Text(
                                            text = stringResource(when (type) {
                                                PlaylistSortType.CUSTOM       -> R.string.sort_by_custom
                                                PlaylistSortType.CREATE_DATE  -> R.string.sort_by_create_date
                                                PlaylistSortType.NAME         -> R.string.sort_by_name
                                                PlaylistSortType.SONG_COUNT   -> R.string.sort_by_song_count
                                                PlaylistSortType.LAST_UPDATED -> R.string.sort_by_last_updated
                                            }),
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        )
                                    }
                                )
                            }
                        }

                        val arrowBg by animateColorAsState(
                            targetValue = if (sortDescending) MaterialTheme.colorScheme.tertiaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                            label = "arrowBg"
                        )
                        val arrowFg by animateColorAsState(
                            targetValue = if (sortDescending) MaterialTheme.colorScheme.onTertiaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                            label = "arrowFg"
                        )
                        IconToggleButton(
                            checked = sortDescending,
                            onCheckedChange = { onSortDescendingChange(it) },
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(arrowBg)
                                .size(36.dp)
                        ) {
                            Icon(
                                painter = painterResource(
                                    if (sortDescending) R.drawable.arrow_downward else R.drawable.arrow_upward
                                ),
                                contentDescription = stringResource(
                                    if (sortDescending) R.string.sort_descending else R.string.sort_ascending
                                ),
                                tint = arrowFg,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            items(playlists) { playlist ->
                val containsSong = playlist.id in playlistsContainingSong
                val rowBg by animateColorAsState(
                    targetValue = if (containsSong)
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    else Color.Transparent,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    label = "playlistBg"
                )
                val checked = playlist.id in selectedPlaylistIds
                val toggle = {
                    if (checked) selectedPlaylistIds.remove(playlist.id) else selectedPlaylistIds.add(playlist.id)
                }
                PlaylistListItem(
                    playlist = playlist,
                    trailingContent = {
                        Checkbox(checked = checked, onCheckedChange = { toggle() })
                    },
                    modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(rowBg)
                    .clickable { toggle() }
                )
            }
        }
    }

    if (showCreatePlaylistDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreatePlaylistDialog = false },
            initialTextFieldValue = initialTextFieldValue,
        )
    }

    if (pendingPlans.isNotEmpty()) {
        val plans = pendingPlans
        DefaultDialog(
            title = { Text(stringResource(R.string.probable_duplicates_title)) },
            buttons = {
                TextButton(onClick = { pendingPlans = emptyList() }) {
                    Text(stringResource(android.R.string.cancel))
                }
                TextButton(onClick = { commitPlans(plans, emptySet()) }) {
                    Text(stringResource(R.string.skip_similar))
                }
                TextButton(onClick = { commitPlans(plans, acceptedProbables.toSet()) }) {
                    Text(stringResource(R.string.add_checked))
                }
            },
            onDismiss = { pendingPlans = emptyList() },
        ) {
            Text(
                text = stringResource(R.string.probable_duplicates_desc),
                textAlign = TextAlign.Start,
                modifier = Modifier.align(Alignment.Start),
            )
            Column(
                modifier =
                    Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
            ) {
                plans.forEach { (target, plan) ->
                    plan.probable.forEach { (candidate, existing) ->
                        val key = "${target.id}|${candidate.id}"
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (key in acceptedProbables) acceptedProbables.remove(key) else acceptedProbables.add(key)
                                    }.padding(vertical = 4.dp),
                        ) {
                            Checkbox(
                                checked = key in acceptedProbables,
                                onCheckedChange = {
                                    if (it) acceptedProbables.add(key) else acceptedProbables.remove(key)
                                },
                            )
                            Column {
                                Text(
                                    text = describe(candidate),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = "≈ ${describe(existing)} · ${target.playlist.name}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun describe(song: Candidate): String =
    listOfNotNull(song.title, song.artist, makeTimeString(song.durationSec * 1000L)).joinToString(" · ")

internal const val MAX_PLAYLIST_DUPLICATES_BATCH_SIZE = 500

internal suspend fun DatabaseDao.playlistDuplicatesBatched(
    playlistId: String,
    songIds: List<String>,
): List<String> =
    if (songIds.size <= MAX_PLAYLIST_DUPLICATES_BATCH_SIZE) {
        playlistDuplicates(playlistId, songIds)
    } else {
        songIds
            .distinct()
            .chunked(MAX_PLAYLIST_DUPLICATES_BATCH_SIZE)
            .flatMap { playlistDuplicates(playlistId, it) }
    }
