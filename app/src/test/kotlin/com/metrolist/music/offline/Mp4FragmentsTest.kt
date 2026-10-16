package com.metrolist.music.offline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class Mp4FragmentsTest {
    private fun box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val size = 8 + payload.size
        out.write(byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()))
        out.write(type.toByteArray(Charsets.US_ASCII))
        out.write(payload)
        return out.toByteArray()
    }

    private fun file(vararg boxes: ByteArray) =
        File.createTempFile("frag", ".m4a").apply { writeBytes(boxes.reduce { a, b -> a + b }) }

    private val ftyp = box("ftyp", ByteArray(16))
    private val audio = box("moof", ByteArray(20) { 7 }) + box("mdat", ByteArray(100) { 3 })

    @Test
    fun biggerMoovWithSameFragments_isIntact() {
        val original = file(ftyp, box("moov", ByteArray(40)), box("sidx", ByteArray(12)), audio)
        val tagged = file(ftyp, box("moov", ByteArray(4000) { 1 }), box("sidx", ByteArray(12)), audio)
        assertTrue(Mp4Fragments.audioIntact(original, tagged))
    }

    @Test
    fun changedAudioBytes_areRejected() {
        val original = file(ftyp, box("moov", ByteArray(40)), audio)
        val broken = file(ftyp, box("moov", ByteArray(60)), box("moof", ByteArray(20) { 7 }), box("mdat", ByteArray(100) { 4 }))
        assertFalse(Mp4Fragments.audioIntact(original, broken))
    }

    @Test
    fun truncatedFile_isRejected() {
        val original = file(ftyp, box("moov", ByteArray(40)), audio)
        val whole = ftyp + box("moov", ByteArray(60)) + audio
        val truncated = File.createTempFile("frag", ".m4a").apply { writeBytes(whole.copyOf(whole.size - 10)) }
        assertFalse(Mp4Fragments.audioIntact(original, truncated))
    }

    @Test
    fun nonFragmentedFile_isNotFragmented() {
        assertFalse(Mp4Fragments.isFragmented(file(ftyp, box("moov", ByteArray(40)), box("mdat", ByteArray(10)))))
        assertTrue(Mp4Fragments.isFragmented(file(ftyp, box("moov", ByteArray(40)), audio)))
    }
}
