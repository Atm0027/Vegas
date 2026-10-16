package com.metrolist.music.playlist

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistAddPlannerTest {
    private fun c(id: String, t: String = id, a: String? = "Artist", d: Int = 200) = Candidate(id, t, a, d)

    @Test
    fun exactDuplicates_areSkipped_newKeepSelectionOrder() {
        val plan = PlaylistAddPlanner.plan(listOf(c("A"), c("B"), c("C")), existing = listOf(c("B")))
        assertEquals(listOf("A", "C"), plan.new)
        assertEquals(listOf("B"), plan.alreadyThere)
        assertEquals(emptyList<Pair<Candidate, Candidate>>(), plan.probable)
    }

    @Test
    fun repeatedIdsInTheBatch_areAddedOnce() {
        val plan = PlaylistAddPlanner.plan(listOf(c("A"), c("A"), c("B")), existing = emptyList())
        assertEquals(listOf("A", "B"), plan.new)
    }

    @Test
    fun probableDuplicates_areSetAsideWithTheirMatch() {
        val existing = c("A1", "Africa", "Toto", 295)
        val video = c("V9", "Africa (Official Video)", "Toto", 297)
        val plan = PlaylistAddPlanner.plan(listOf(video, c("X")), existing = listOf(existing))
        assertEquals(listOf("X"), plan.new)
        assertEquals(listOf(video to existing), plan.probable)
    }

    @Test
    fun probableWithinTheBatch_isDetectedToo() {
        val album = c("A1", "Africa", "Toto", 295)
        val video = c("V9", "Africa (Official Video)", "Toto", 297)
        val plan = PlaylistAddPlanner.plan(listOf(album, video), existing = emptyList())
        assertEquals(listOf("A1"), plan.new)
        assertEquals(listOf(video to album), plan.probable)
    }

    @Test
    fun finalOrder_keepsSelectionOrderForAcceptedProbables() {
        val existing = c("A1", "Africa", "Toto", 295)
        val video = c("V9", "Africa (Official Video)", "Toto", 297)
        val plan = PlaylistAddPlanner.plan(listOf(c("X"), video, c("Y")), existing = listOf(existing))
        assertEquals(listOf("X", "V9", "Y"), plan.idsToAdd(acceptedProbables = setOf("V9")))
        assertEquals(listOf("X", "Y"), plan.idsToAdd(acceptedProbables = emptySet()))
    }

    @Test
    fun repeatedExistingSong_isCountedOnce() {
        val plan = PlaylistAddPlanner.plan(listOf(c("B"), c("B")), existing = listOf(c("B")))
        assertEquals(listOf("B"), plan.alreadyThere)
    }
}
