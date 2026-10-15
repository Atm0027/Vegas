package com.metrolist.music.playback

import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class ShuffleOrdersTest {
    private fun assertPermutation(order: IntArray, size: Int) =
        assertEquals((0 until size).toList(), order.sorted())

    @Test
    fun fresh_putsCurrentFirst_andIsPermutation() {
        val order = ShuffleOrders.fresh(4, 10, null, Random(1))
        assertEquals(4, order[0])
        assertPermutation(order, 10)
    }

    @Test
    fun fresh_playlistFirst_keepsUserAddedLast() {
        val order = ShuffleOrders.fresh(2, 10, setOf(6, 7, 8, 9), Random(1))
        assertEquals(2, order[0])
        assertEquals((0 until 6).toSet(), order.slice(0 until 6).toSet())
        assertPermutation(order, 10)
    }

    @Test
    fun enable_keepsLinearHistoryBeforeCurrent() {
        // Listening in order, now at song 3; switching shuffle on must keep 0,1,2 as "previous".
        val order = ShuffleOrders.enable(3, 8, null, Random(1))
        assertArrayEquals(intArrayOf(0, 1, 2, 3), order.copyOfRange(0, 4))
        assertPermutation(order, 8)
    }

    @Test
    fun enable_playlistFirst_keepsUserAddedLast() {
        val order = ShuffleOrders.enable(1, 8, setOf(6, 7), Random(1))
        assertArrayEquals(intArrayOf(0, 1), order.copyOfRange(0, 2))
        assertEquals(setOf(2, 3, 4, 5), order.slice(2..5).toSet())
        assertEquals(setOf(6, 7), order.slice(6..7).toSet())
    }

    @Test
    fun extend_keepsHistoryBeforeCurrent() {
        // Played 7 -> 2 -> 9, now on 9; pending 0,5,... A page load adds 10..12, which
        // ExoPlayer inserted at random spots, including inside the history.
        val withInserted = intArrayOf(7, 11, 2, 9, 0, 10, 5, 1, 3, 4, 12, 6, 8)
        val order = ShuffleOrders.extend(withInserted, 9, (10..12).toSet(), null, Random(1))
        assertArrayEquals(intArrayOf(7, 2, 9), order.copyOfRange(0, 3))
        assertPermutation(order, 13)
    }

    @Test
    fun extend_playlistFirst_keepsUserAddedLast() {
        val withInserted = intArrayOf(3, 10, 0, 1, 11, 2)
        val order = ShuffleOrders.extend(withInserted, 3, setOf(10, 11), setOf(10, 11), Random(1))
        assertEquals(3, order[0])
        assertEquals(setOf(0, 1, 2), order.slice(1..3).toSet())
        assertEquals(setOf(10, 11), order.slice(4..5).toSet())
    }

    @Test
    fun extend_playlistFirst_twoConsecutiveAdds_keepBothAfterPlaylist() {
        // Playlist 0..4 playing at 0; user added 5 earlier (pending), now adds 6.
        val order = ShuffleOrders.extend(intArrayOf(0, 5, 2, 6, 1, 3, 4), 0, setOf(6), setOf(5, 6), Random(1))
        assertEquals(setOf(1, 2, 3, 4), order.slice(1..4).toSet())
        assertEquals(setOf(5, 6), order.slice(5..6).toSet())
    }

    @Test
    fun extend_playlistFirst_pageLoadGoesBeforeUserAdded() {
        // Playlist 0..3 at 0, user added 4; a playlist page load appends 5,6 (not user-added).
        val order = ShuffleOrders.extend(intArrayOf(0, 1, 4, 5, 2, 6, 3), 0, setOf(5, 6), setOf(4), Random(1))
        assertEquals(setOf(1, 2, 3, 5, 6), order.slice(1..5).toSet())
        assertEquals(4, order[6])
    }

    @Test
    fun extend_isValidPermutation_afterRemovals() {
        val order = ShuffleOrders.extend(intArrayOf(4, 0, 2, 1, 3), 2, setOf(4), null, Random(1))
        assertArrayEquals(intArrayOf(0, 2), order.copyOfRange(0, 2))
        assertPermutation(order, 5)
    }

    @Test
    fun extend_currentMissing_fallsBackToFresh() {
        val order = ShuffleOrders.extend(intArrayOf(0, 1, 2), 5, emptySet(), null, Random(1))
        assertPermutation(order, 3)
    }

    @Test
    fun remainingAfter_countsShufflePosition() {
        assertEquals(2, ShuffleOrders.remainingAfter(intArrayOf(7, 2, 9, 0, 5), 9))
        assertEquals(0, ShuffleOrders.remainingAfter(intArrayOf(7, 2, 9), 9))
    }

    @Test
    fun toIntArray_readsDefaultShuffleOrder() {
        val src = intArrayOf(3, 0, 2, 1)
        assertArrayEquals(src, DefaultShuffleOrder(src, 42L).toIntArray(4))
    }
}
