/**
 * Vegas: a song that started from the stream keeps the stream until the player leaves it. Its
 * download may finish mid-song, and the file's byte layout differs from the stream's, so switching
 * on a seek or the next range request would break the container. Preloaded items lock too.
 * Licensed under GPL-3.0
 */

package com.metrolist.music.offline

import java.util.concurrent.ConcurrentHashMap

class SourceLock {
    private val streaming = ConcurrentHashMap.newKeySet<String>()

    fun isLocked(mediaId: String): Boolean = mediaId in streaming

    fun useLocal(mediaId: String, hasLocalFile: Boolean): Boolean {
        if (mediaId in streaming) return false
        if (hasLocalFile) return true
        streaming += mediaId
        return false
    }

    fun left(mediaId: String) {
        streaming -= mediaId
    }
}
