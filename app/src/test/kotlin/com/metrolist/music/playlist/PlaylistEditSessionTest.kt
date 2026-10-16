package com.metrolist.music.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PlaylistEditSessionTest {
    private fun session(ids: List<Int>) = PlaylistEditSession(ids, "Torre", null)

    @Test
    fun move_reassignsContiguousPositions() {
        val s = session(listOf(10, 11, 12))
        s.move(0, 2)
        assertEquals(listOf(11, 12, 10), s.order)
        assertEquals(mapOf(11 to 0, 12 to 1, 10 to 2), s.commit().positions)
    }

    @Test
    fun removeAndMove_leaveNoGaps() {
        val s = session(listOf(10, 11, 12, 13))
        s.toggleRemove(11)
        s.move(0, 2)
        val r = s.commit()
        assertEquals(setOf(11), r.removed)
        assertEquals(listOf(12, 13, 10), s.order)
        assertEquals(mapOf(12 to 0, 13 to 1, 10 to 2), r.positions)
    }

    @Test
    fun toggleRemoveTwice_restoresSong() {
        val s = session(listOf(10, 11))
        s.toggleRemove(11)
        s.toggleRemove(11)
        assertEquals(listOf(10, 11), s.order)
        assertFalse(s.commit().changed)
    }

    @Test
    fun noChanges_isNotChanged() {
        assertFalse(session(listOf(1, 2, 3)).commit().changed)
    }

    @Test
    fun renameAndThumbnail_areChanges() {
        val s = session(listOf(1))
        s.rename("Rock")
        s.setThumbnail("content://cover")
        val r = s.commit()
        assertTrue(r.changed)
        assertEquals("Rock", r.name)
        assertEquals("content://cover", r.thumbnailUrl)
    }

    @Test
    fun manyRandomEdits_keepPositionsDense() {
        val ids = (1000 until 1307).toList()
        val s = session(ids)
        val random = Random(42)
        repeat(500) {
            val n = s.order.size
            s.move(random.nextInt(n), random.nextInt(n))
            if (it % 50 == 0) s.toggleRemove(s.order[random.nextInt(s.order.size)])
        }
        val r = s.commit()
        assertEquals((0 until 307 - r.removed.size).toList(), r.positions.values.sorted())
        assertEquals(ids.toSet(), r.positions.keys + r.removed)
        assertTrue(r.positions.keys.none { it in r.removed })
    }
}
