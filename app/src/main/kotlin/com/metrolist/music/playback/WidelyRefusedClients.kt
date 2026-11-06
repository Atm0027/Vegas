/**
 * Vegas: when YouTube refuses one client's streams (HTTP 403) it usually does so for every song for
 * a while. A client refused on a couple of different songs is skipped for all songs for some
 * minutes, so the next song does not pay for the same 403 again.
 * Licensed under GPL-3.0
 */

package com.metrolist.music.playback

class WidelyRefusedClients(
    private val minSongs: Int = 2,
    private val windowMs: Long = 10 * 60 * 1000L,
) {
    private val refusals = HashMap<String, HashMap<String, Long>>()

    @Synchronized
    fun record(
        client: String,
        videoId: String,
        nowMs: Long,
    ) {
        refusals.getOrPut(client) { HashMap() }[videoId] = nowMs
    }

    @Synchronized
    fun clients(nowMs: Long): Set<String> {
        val result = HashSet<String>()
        val iterator = refusals.entries.iterator()
        while (iterator.hasNext()) {
            val (client, songs) = iterator.next()
            songs.values.removeAll { nowMs - it !in 0 until windowMs }
            if (songs.isEmpty()) iterator.remove() else if (songs.size >= minSongs) result += client
        }
        return result
    }

    @Synchronized
    fun clear() = refusals.clear()
}
