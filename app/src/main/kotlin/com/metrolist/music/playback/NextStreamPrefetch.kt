/**
 * Vegas: resolving a song's stream (player API, PoToken, client fallbacks) is what makes a song
 * wait before it starts. Once the current song plays, the next one is resolved in the background
 * so a skip or the natural transition finds its stream address ready.
 * Licensed under GPL-3.0
 */

package com.metrolist.music.playback

object NextStreamPrefetch {
    /** Grace period so the current song's own loading is never competed with. */
    const val DELAY_MS = 3_000L

    /** The song worth resolving ahead, or null when it would play without a network resolution anyway. */
    fun target(
        nextId: String?,
        online: Boolean,
        isLocal: (String) -> Boolean,
        isUrlCached: (String) -> Boolean,
        isAudioCached: (String) -> Boolean,
    ): String? =
        nextId?.takeIf { online && !isLocal(it) && !isUrlCached(it) && !isAudioCached(it) }
}
