/**
 * Vegas: without a usable connection the player skips ahead, in play order (shuffle and repeat
 * included), to the next song that can play offline.
 * Same as the phone's copy in app/…/offline; keep both in step.
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.desktop.offline

object OfflineSkip {
    /** A song that is not downloaded and stays this long without loading counts as unreachable. */
    const val STALL_MS = 4_000L

    /**
     * [next] returns the following index in play order, or -1 at the end. Walks at most [itemCount]
     * steps so repeat-all cannot loop forever, and never returns [current].
     */
    fun nextPlayableIndex(
        current: Int,
        itemCount: Int,
        next: (Int) -> Int,
        isPlayable: (Int) -> Boolean,
    ): Int? {
        var index = current
        repeat(itemCount) {
            index = next(index)
            if (index < 0 || index == current) return null
            if (isPlayable(index)) return index
        }
        return null
    }
}
