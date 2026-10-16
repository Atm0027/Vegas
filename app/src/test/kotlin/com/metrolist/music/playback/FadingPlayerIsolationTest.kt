package com.metrolist.music.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class FadingPlayerIsolationTest {
    @Test
    fun removesItemsOnBothSides_lastRangeFirst() =
        assertEquals(listOf(6 until 10, 0 until 5), FadingPlayerIsolation.rangesToRemove(currentIndex = 5, itemCount = 10))

    @Test
    fun currentFirst_onlyRemovesAfter() =
        assertEquals(listOf(1 until 4), FadingPlayerIsolation.rangesToRemove(currentIndex = 0, itemCount = 4))

    @Test
    fun currentLast_onlyRemovesBefore() =
        assertEquals(listOf(0 until 3), FadingPlayerIsolation.rangesToRemove(currentIndex = 3, itemCount = 4))

    @Test
    fun singleItem_removesNothing() =
        assertEquals(emptyList<IntRange>(), FadingPlayerIsolation.rangesToRemove(currentIndex = 0, itemCount = 1))
}
