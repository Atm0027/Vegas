/**
 * Vegas: the pending list shrinks by one at every finished download; queueing the whole list again
 * each time costs O(n²) WorkManager writes over a large sync. Only songs new to the list are queued.
 * Licensed under GPL-3.0
 */

package com.metrolist.music.offline

class PendingDiff {
    private var previous: Set<String> = emptySet()

    fun fresh(ids: List<String>): List<String> {
        val result = ids.filterNot { it in previous }
        previous = ids.toSet()
        return result
    }
}
