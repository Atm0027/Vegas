package com.metrolist.music.offline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceLockTest {
    @Test
    fun songThatStartedStreaming_staysOnTheStreamWhenItsDownloadFinishes() {
        val lock = SourceLock()
        assertFalse(lock.useLocal("a", hasLocalFile = false))
        assertFalse(lock.useLocal("a", hasLocalFile = true))
    }

    @Test
    fun nextSongOrReplay_usesTheDownloadedFile() {
        val lock = SourceLock()
        lock.useLocal("a", hasLocalFile = false)
        assertTrue(lock.useLocal("b", hasLocalFile = true))
        lock.left("a")
        assertTrue(lock.useLocal("a", hasLocalFile = true))
    }

    @Test
    fun songThatStartedLocal_keepsUsingTheFile() {
        val lock = SourceLock()
        assertTrue(lock.useLocal("a", hasLocalFile = true))
        assertTrue(lock.useLocal("a", hasLocalFile = true))
    }

    @Test
    fun preloadedNextSong_staysLockedWhenTheCurrentOneIsLeft() {
        val lock = SourceLock()
        lock.useLocal("a", hasLocalFile = false)
        lock.useLocal("b", hasLocalFile = false)
        lock.left("a")
        assertFalse(lock.useLocal("b", hasLocalFile = true))
    }
}
