package com.metrolist.music.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CrashLogStoreTest {
    @Test
    fun crashesAreKept_newestFiveOnly() {
        val dir = Files.createTempDirectory("crashes").toFile()
        val store = CrashLogStore(dir, keep = 5)
        repeat(7) { store.save("crash $it", timestamp = 1_000L + it) }
        val files = dir.listFiles()!!.sortedBy { it.name }
        assertEquals(5, files.size)
        assertTrue(files.first().readText().contains("crash 2"))
        assertTrue(files.last().readText().contains("crash 6"))
    }
}
