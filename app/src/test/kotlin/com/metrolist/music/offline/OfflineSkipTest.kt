package com.metrolist.music.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineSkipTest {
    private fun order(vararg pairs: Pair<Int, Int>): (Int) -> Int {
        val map = pairs.toMap()
        return { map[it] ?: -1 }
    }

    private val linear: (Int) -> Int = { if (it < 4) it + 1 else -1 }

    @Test
    fun `linear queue picks the next downloaded`() {
        assertEquals(3, OfflineSkip.nextPlayableIndex(0, 5, linear) { it == 3 })
    }

    @Test
    fun `shuffle order is followed`() {
        val shuffled = order(0 to 3, 3 to 1, 1 to 4, 4 to 2)
        assertEquals(1, OfflineSkip.nextPlayableIndex(0, 5, shuffled) { it in setOf(1, 2) })
    }

    @Test
    fun `nothing playable gives null`() {
        assertNull(OfflineSkip.nextPlayableIndex(0, 5, linear) { false })
        assertNull(OfflineSkip.nextPlayableIndex(0, 5, linear) { it == 0 })
    }

    @Test
    fun `repeat all cycle ends without looping forever`() {
        val cycle = order(0 to 1, 1 to 2, 2 to 0)
        assertNull(OfflineSkip.nextPlayableIndex(1, 3, cycle) { false })
        assertNull(OfflineSkip.nextPlayableIndex(1, 3, cycle) { it == 1 })
    }
}
