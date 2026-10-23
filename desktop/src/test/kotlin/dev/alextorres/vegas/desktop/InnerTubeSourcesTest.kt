package dev.alextorres.vegas.desktop

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.models.YouTubeLocale
import com.metrolist.innertube.models.filterPodcasts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

class InnerTubeSourcesTest {
    @Test
    fun youtubeFacadeCompilesOnDesktop() {
        // Compiling is the test: YouTube and filterPodcasts come from the shared innertube sources.
        assertNotNull(YouTube::class)
        assertEquals(emptyList<YTItem>(), emptyList<SongItem>().filterPodcasts())
    }

    @Test
    @Ignore("live network; run manually")
    fun liveSearchFindsSongs() = runBlocking {
        YouTube.locale = YouTubeLocale(gl = "ES", hl = "es")
        val page = YouTube.search("Estopa Me Quedaré", YouTube.SearchFilter.FILTER_SONG).getOrThrow()
        assertTrue(page.items.isNotEmpty())
    }
}
