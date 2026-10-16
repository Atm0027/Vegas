package com.metrolist.music.offline

import org.junit.Assert.assertEquals
import org.junit.Test

class PendingDiffTest {
    @Test
    fun finishedDownload_doesNotRequeueTheRemainingSongs() {
        val diff = PendingDiff()
        assertEquals(listOf("a", "b", "c"), diff.fresh(listOf("a", "b", "c")))
        assertEquals(emptyList<String>(), diff.fresh(listOf("b", "c")))
    }

    @Test
    fun songAddedToATrackedList_isQueued_andAnUnmarkedOneComesBackWhenMarkedAgain() {
        val diff = PendingDiff()
        diff.fresh(listOf("a"))
        assertEquals(listOf("d"), diff.fresh(listOf("a", "d")))
        diff.fresh(listOf("d"))
        assertEquals(listOf("a"), diff.fresh(listOf("a", "d")))
    }
}
