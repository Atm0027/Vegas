/**
 * Vegas: YouTube's m4a streams are fragmented MP4 (moov + sidx + moof/mdat pairs). Tagging only grows
 * moov; the fragments use offsets relative to themselves, so moving them is harmless as long as
 * their bytes stay identical. jaudiotagger's own offset check does not understand this layout and
 * reports a false error, so the tagged copy is checked here instead.
 * Licensed under GPL-3.0
 */

package com.metrolist.music.offline

import java.io.File
import java.io.RandomAccessFile

object Mp4Fragments {
    private data class Box(val type: String, val offset: Long, val size: Long)

    private val IGNORED = setOf("moov", "free", "skip")

    fun isFragmented(file: File): Boolean = boxes(file)?.any { it.type == "moof" } == true

    /** True when every top-level box other than moov/free is byte-for-byte the same in both files. */
    fun audioIntact(original: File, tagged: File): Boolean {
        val before = boxes(original)?.filter { it.type !in IGNORED } ?: return false
        val after = boxes(tagged)?.filter { it.type !in IGNORED } ?: return false
        if (before.map { it.type to it.size } != after.map { it.type to it.size }) return false
        RandomAccessFile(original, "r").use { a ->
            RandomAccessFile(tagged, "r").use { b ->
                return before.zip(after).all { (x, y) -> sameBytes(a, x.offset, b, y.offset, x.size) }
            }
        }
    }

    /** Top-level boxes, or null unless they cover the file exactly. */
    private fun boxes(file: File): List<Box>? {
        val length = file.length()
        val result = mutableListOf<Box>()
        RandomAccessFile(file, "r").use { raf ->
            var pos = 0L
            while (pos < length) {
                if (length - pos < 8) return null
                raf.seek(pos)
                var size = raf.readInt().toLong() and 0xFFFFFFFFL
                val type = ByteArray(4).also { raf.readFully(it) }.toString(Charsets.ISO_8859_1)
                if (size == 1L) size = raf.readLong()
                if (size == 0L) size = length - pos
                if (size < 8 || pos + size > length) return null
                result += Box(type, pos, size)
                pos += size
            }
        }
        return result
    }

    private fun sameBytes(a: RandomAccessFile, aOffset: Long, b: RandomAccessFile, bOffset: Long, size: Long): Boolean {
        val bufA = ByteArray(64 * 1024)
        val bufB = ByteArray(64 * 1024)
        var done = 0L
        while (done < size) {
            val n = minOf(bufA.size.toLong(), size - done).toInt()
            a.seek(aOffset + done)
            a.readFully(bufA, 0, n)
            b.seek(bOffset + done)
            b.readFully(bufB, 0, n)
            for (i in 0 until n) if (bufA[i] != bufB[i]) return false
            done += n
        }
        return true
    }
}
