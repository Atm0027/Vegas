package com.metrolist.music.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineFileNameTest {
    private val id = "abc123DEF_-"

    @Test
    fun `builds artist - title with video id`() {
        assertEquals(
            "Fito & Fitipaldis - Soldadito marinero [abc123DEF_-].m4a",
            OfflineFileName.build("Fito & Fitipaldis", "Soldadito marinero", id, "m4a"),
        )
    }

    @Test
    fun `replaces invalid characters`() {
        assertEquals(
            "A_B - C_D_E_F_G_H_I_J_K_L [abc123DEF_-].m4a",
            OfflineFileName.build("A/B", "C\\D:E*F?G\"H<I>J|K\u0001L", id, "m4a"),
        )
    }

    @Test
    fun `long title is trimmed but id and extension are kept`() {
        val name = OfflineFileName.build("Artist", "x".repeat(300), id, "m4a")
        assertTrue(name.endsWith(" [abc123DEF_-].m4a"))
        assertTrue(name.length <= 200)
    }

    @Test
    fun `missing artist omits the prefix`() {
        assertEquals("X [abc123DEF_-].m4a", OfflineFileName.build(null, "X", id, "m4a"))
        assertEquals("X [abc123DEF_-].m4a", OfflineFileName.build("", "X", id, "m4a"))
    }

    @Test
    fun `video id is the last bracket before the extension`() {
        assertEquals(id, OfflineFileName.videoIdOf("A - B (Live) [x] [abc123DEF_-].m4a"))
        assertNull(OfflineFileName.videoIdOf("foo.m4a"))
        assertNull(OfflineFileName.videoIdOf("foo [short].m4a"))
    }

    @Test
    fun `parse round trips`() {
        val parsed = OfflineFileName.parse(OfflineFileName.build("Estopa", "Como Camarón", id, "m4a"))!!
        assertEquals("Estopa", parsed.artist)
        assertEquals("Como Camarón", parsed.title)
        assertEquals(id, parsed.videoId)

        val noArtist = OfflineFileName.parse(OfflineFileName.build(null, "Solo", id, "webm"))!!
        assertNull(noArtist.artist)
        assertEquals("Solo", noArtist.title)
    }

    @Test
    fun `recognises the copy MediaStore names with a number suffix`() {
        assertEquals("UYUUb6Dl5D8", OfflineFileName.videoIdOf("Eladio Carrión - Hugo [UYUUb6Dl5D8] (1).m4a"))
    }
}
