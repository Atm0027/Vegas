package com.metrolist.music.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class EmptyMediaRequestTest {
    @Test
    fun somethingToPlay_passesThrough() {
        assertEquals(listOf("a", "b"), EmptyMediaRequest.orKeepQueue(listOf("a", "b"), "playlist/x/"))
    }

    @Test(expected = EmptyMediaRequest.NothingToPlay::class)
    fun emptyLikedSongs_failsInsteadOfWipingTheQueue() {
        EmptyMediaRequest.orKeepQueue(emptyList<String>(), "playlist/LP_LIKED/__shuffle__")
    }
}
