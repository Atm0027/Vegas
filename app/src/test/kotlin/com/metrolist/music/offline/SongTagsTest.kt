package com.metrolist.music.offline

import org.junit.Assert.assertEquals
import org.junit.Test

class SongTagsTest {
    @Test
    fun `artists are joined with commas`() {
        assertEquals("Fito, Estopa", SongTags("t", listOf("Fito", "Estopa"), null, null).artistLine())
        assertEquals("", SongTags("t", emptyList(), null, null).artistLine())
    }
}
