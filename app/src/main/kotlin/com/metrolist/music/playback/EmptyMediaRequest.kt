/**
 * Vegas: a play request that resolves to nothing must leave the current queue alone.
 * Licensed under GPL-3.0
 */

package com.metrolist.music.playback

/**
 * Media3 replaces the player's queue with whatever `onSetMediaItems` returns, even an empty list,
 * and then asks it to play: silence, and the saved queue is gone. A failed future is ignored
 * instead ("the session is free to ignore these requests", `MediaSessionLegacyStub`), so an empty
 * result is turned into a failure.
 *
 * Seen with JARVIS on 2026-11-07: it woke the service and asked for the liked songs in shuffle
 * while the saved queue was still being restored; with no liked songs that wiped the restored queue.
 */
object EmptyMediaRequest {
    class NothingToPlay(request: String?) : IllegalStateException("Nothing to play for '$request'; queue left as it was")

    /** Returns [items] when there is something to play; throws [NothingToPlay] otherwise. */
    fun <T> orKeepQueue(items: List<T>, request: String?): List<T> {
        if (items.isEmpty()) throw NothingToPlay(request)
        return items
    }
}
