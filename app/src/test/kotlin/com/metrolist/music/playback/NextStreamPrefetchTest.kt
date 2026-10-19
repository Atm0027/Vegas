package com.metrolist.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NextStreamPrefetchTest {
    private fun target(
        nextId: String? = "next",
        online: Boolean = true,
        local: Boolean = false,
        urlCached: Boolean = false,
        audioCached: Boolean = false,
    ) = NextStreamPrefetch.target(nextId, online, isLocal = { local }, isUrlCached = { urlCached }, isAudioCached = { audioCached })

    @Test
    fun nextStreamingSong_isResolvedAhead() = assertEquals("next", target())

    @Test
    fun nothingToDo_whenOfflineLocalOrAlreadyCached() {
        assertNull(target(nextId = null))
        assertNull(target(online = false))
        assertNull(target(local = true))
        assertNull(target(urlCached = true))
        assertNull(target(audioCached = true))
    }
}
