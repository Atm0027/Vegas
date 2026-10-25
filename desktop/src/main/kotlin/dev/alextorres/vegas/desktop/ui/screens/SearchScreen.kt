/**
 * Vegas desktop: search with suggestions while typing and the phone's filters (no podcasts).
 * Playing a song from the results plays just that song; the radio carries on after it.
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.desktop.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.models.filterExplicit
import com.metrolist.innertube.models.filterPodcasts
import com.metrolist.innertube.models.filterVideoSongs
import dev.alextorres.vegas.desktop.playback.toTrack
import dev.alextorres.vegas.desktop.ui.Strings
import kotlinx.coroutines.delay

private enum class SearchFilterChip(val label: String, val filter: YouTube.SearchFilter?) {
    ALL(Strings.ALL, null),
    SONGS(Strings.SONGS, YouTube.SearchFilter.FILTER_SONG),
    ALBUMS(Strings.ALBUMS, YouTube.SearchFilter.FILTER_ALBUM),
    ARTISTS(Strings.ARTISTS, YouTube.SearchFilter.FILTER_ARTIST),
    PLAYLISTS(Strings.PLAYLISTS, YouTube.SearchFilter.FILTER_FEATURED_PLAYLIST),
}

/** The search text survives leaving and coming back to the Search tab. */
private object SearchMemory {
    var text = ""
    var submitted = ""
}

@Composable
fun SearchScreen() {
    var text by remember { mutableStateOf(SearchMemory.text) }
    var submitted by remember { mutableStateOf(SearchMemory.submitted) }
    var chip by rememberSaveable { mutableStateOf(SearchFilterChip.ALL) }
    var suggestions by remember { mutableStateOf(emptyList<String>()) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { if (submitted.isEmpty()) focus.requestFocus() }
    LaunchedEffect(text) {
        SearchMemory.text = text
        if (text.isBlank() || text == submitted) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        delay(300)
        val found = YouTube.searchSuggestions(text).getOrNull()?.queries.orEmpty().take(8)
        // The search may have been submitted while suggestions were loading.
        if (text != submitted) suggestions = found
    }

    fun submit(query: String) {
        text = query
        submitted = query.trim()
        SearchMemory.submitted = submitted
        suggestions = emptyList()
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text(Strings.SEARCH_HINT) },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = {
                if (text.isNotEmpty()) IconButton(onClick = { text = "" }) { Icon(Icons.Rounded.Close, null) }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (text.isNotBlank()) submit(text) }),
            modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().focusRequester(focus),
        )
        if (suggestions.isNotEmpty()) {
            Column(Modifier.widthIn(max = 720.dp).padding(vertical = 8.dp)) {
                suggestions.forEach { s ->
                    Text(
                        s,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().clickable { submit(s) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }
        if (submitted.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 12.dp)) {
                SearchFilterChip.entries.forEach { c ->
                    FilterChip(selected = chip == c, onClick = { chip = c }, label = { Text(c.label) })
                }
            }
            Results(submitted, chip)
        }
    }
}

@Composable
private fun Results(query: String, chip: SearchFilterChip) {
    val deps = LocalVegas.current
    val prefs by deps.settings.flow.collectAsState()
    val (load, retry) = rememberLoad(listOf(query, chip, prefs.hideExplicit, prefs.hideVideoSongs)) {
        val filter = chip.filter
        if (filter == null) {
            YouTube.searchSummary(query).map { page ->
                page.summaries.map { Strings.searchSection(it.title) to it.items.filterPodcasts().filterExplicit(prefs.hideExplicit).filterVideoSongs(prefs.hideVideoSongs) }.filter { it.second.isNotEmpty() }
            }
        } else {
            YouTube.search(query, filter).map { listOf(chip.label to it.items.filterPodcasts().filterExplicit(prefs.hideExplicit).filterVideoSongs(prefs.hideVideoSongs)) }
        }
    }
    LoadContent(load, retry) { groups ->
        if (groups.all { it.second.isEmpty() }) {
            Text(Strings.NO_RESULTS, modifier = Modifier.padding(16.dp))
            return@LoadContent
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            groups.forEach { (title, items) ->
                val songs = items.filterIsInstance<SongItem>()
                val others = items.filterNot { it is SongItem }
                if (songs.isNotEmpty()) {
                    item { SectionTitle(title) }
                    items(songs) { song -> SongRow(song.toTrack(), onPlay = { deps.controller.playQueue(listOf(song.toTrack()), 0) }) }
                }
                if (others.isNotEmpty()) item { ItemRow(if (songs.isEmpty()) title else "", others as List<YTItem>) }
            }
        }
    }
}
