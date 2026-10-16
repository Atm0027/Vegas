/**
 * Vega: splits the songs being added to one playlist into new ones, exact duplicates (skipped) and
 * probable duplicates (the user decides). Songs earlier in the same batch count as already present.
 * Licensed under GPL-3.0
 */

package com.metrolist.music.playlist

object PlaylistAddPlanner {
    fun plan(candidates: List<Candidate>, existing: List<Candidate>): AddPlan {
        val known = existing.toMutableList()
        val ordered = mutableListOf<String>()
        val new = mutableListOf<String>()
        val alreadyThere = mutableListOf<String>()
        val probable = mutableListOf<Pair<Candidate, Candidate>>()

        candidates.forEach { candidate ->
            if (candidate.id in ordered) return@forEach
            when (val match = DuplicateMatcher.match(candidate, known)) {
                is Match.Exact -> alreadyThere += candidate.id
                is Match.Probable -> {
                    ordered += candidate.id
                    probable += candidate to match.existing
                }
                Match.New -> {
                    ordered += candidate.id
                    new += candidate.id
                    known += candidate
                }
            }
        }
        return AddPlan(ordered, new, alreadyThere, probable)
    }
}

data class AddPlan(
    /** New and probable songs in selection order. */
    val ordered: List<String>,
    val new: List<String>,
    val alreadyThere: List<String>,
    /** Candidate to the song it looks like. */
    val probable: List<Pair<Candidate, Candidate>>,
) {
    fun idsToAdd(acceptedProbables: Set<String>): List<String> {
        val newIds = new.toSet()
        return ordered.filter { it in newIds || it in acceptedProbables }
    }
}
