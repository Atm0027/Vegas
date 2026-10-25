/**
 * Vegas desktop: YouTube Music home, without podcasts (as on the phone).
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.desktop.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.filterExplicit
import com.metrolist.innertube.models.filterPodcasts
import com.metrolist.innertube.models.filterVideoSongs

@Composable
fun HomeScreen() {
    val deps = LocalVegas.current
    val prefs by deps.settings.flow.collectAsState()
    val (load, retry) = rememberLoad(prefs.hideExplicit to prefs.hideVideoSongs) {
        YouTube.home().map { page ->
            page.sections.map { it.title to it.items.filterPodcasts().filterExplicit(prefs.hideExplicit).filterVideoSongs(prefs.hideVideoSongs) }.filter { it.second.isNotEmpty() }
        }
    }
    LoadContent(load, retry) { sections ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)) {
            items(sections) { (title, items) -> ItemRow(title, items) }
        }
    }
}
