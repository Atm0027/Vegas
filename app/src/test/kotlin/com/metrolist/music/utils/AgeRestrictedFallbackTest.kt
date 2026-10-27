package com.metrolist.music.utils

import com.metrolist.innertube.models.Artist
import com.metrolist.innertube.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Test

class AgeRestrictedFallbackTest {
    private val original =
        AgeRestrictedFallback.Original(
            videoId = "r-tr7dK2cIA",
            title = "She Like 2 Fuck",
            artists = listOf("Jay Wheeler", "Luar La L"),
            durationSeconds = 386,
        )

    private fun song(
        id: String,
        title: String,
        artist: String = "Jay Wheeler",
        duration: Int? = 386,
        explicit: Boolean = true,
    ) = SongItem(id = id, title = title, artists = listOf(Artist(artist, null)), duration = duration, thumbnail = "", explicit = explicit)

    @Test
    fun `keeps other uploads of the same song and puts clean versions first`() {
        val results =
            listOf(
                song("r-tr7dK2cIA", "She Like 2 Fuck"),
                song("lyrics", "Jay Wheeler, Luar La L - She Like 2 Fuck (Letra/Lyrics)", artist = "ViveLaMusica", duration = 381),
                song("clean", "She Like 2 Fuck", explicit = false),
            )

        assertEquals(listOf("clean", "lyrics"), AgeRestrictedFallback.candidates(original, results))
    }

    @Test
    fun `skips variants, other artists and very different lengths`() {
        val results =
            listOf(
                song("remix", "She Like 2 Fuck (Remix)"),
                song("live", "She Like 2 Fuck - En Vivo"),
                song("other", "She Like 2 Fuck", artist = "Someone Else"),
                song("long", "She Like 2 Fuck", duration = 600),
                song("other-song", "La Curiosidad"),
            )

        assertEquals(emptyList<String>(), AgeRestrictedFallback.candidates(original, results))
    }

    @Test
    fun `query and base title ignore featuring credits`() {
        assertEquals("Jay Wheeler She Like 2 Fuck", AgeRestrictedFallback.query(original))
        assertEquals("Song", AgeRestrictedFallback.baseTitle("Song (feat. X) [Letra]"))
        assertEquals("Song", AgeRestrictedFallback.baseTitle("Song ft. X"))
    }
}
