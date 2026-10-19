/**
 * Vegas: writes title, artists, album and cover into a downloaded m4a, so the file looks right in
 * the Music/Vegas folder and in other players.
 * Licensed under GPL-3.0
 */

package com.metrolist.music.offline

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.audio.exceptions.CannotWriteException
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.images.AndroidArtwork
import java.io.File

data class SongTags(
    val title: String,
    val artists: List<String>,
    val album: String?,
    val cover: ByteArray?,
)

fun SongTags.artistLine(): String = artists.joinToString(", ")

object M4aTagger {
    /**
     * Tags a copy and swaps it in only when it is safe: a clean write, or, for fragmented MP4 where
     * jaudiotagger's offset check misfires, a copy whose audio fragments are byte-identical.
     * Otherwise [file] is left untouched and the error propagates.
     */
    fun write(file: File, tags: SongTags) {
        val copy = File(file.parentFile, file.name + ".tag.m4a")
        file.copyTo(copy, overwrite = true)
        try {
            try {
                tag(copy, tags)
            } catch (e: CannotWriteException) {
                if (!(Mp4Fragments.isFragmented(file) && Mp4Fragments.audioIntact(file, copy))) throw e
            }
            if (Mp4Fragments.isFragmented(file) && !Mp4Fragments.audioIntact(file, copy)) {
                throw CannotWriteException("Tagged copy changed the audio fragments")
            }
            if (!copy.renameTo(file)) {
                copy.copyTo(file, overwrite = true)
            }
        } finally {
            copy.delete()
        }
    }

    private fun tag(file: File, tags: SongTags) {
        TagOptionSingleton.getInstance().isAndroid = true
        val audio = AudioFileIO.readAs(file, "m4a")
        val tag = audio.tagOrCreateAndSetDefault
        tag.setField(FieldKey.TITLE, tags.title)
        if (tags.artists.isNotEmpty()) {
            tag.setField(FieldKey.ARTIST, tags.artistLine())
            tag.setField(FieldKey.ALBUM_ARTIST, tags.artists.first())
        }
        tags.album?.takeIf { it.isNotBlank() }?.let { tag.setField(FieldKey.ALBUM, it) }
        tags.cover?.let { bytes ->
            val artwork = AndroidArtwork().apply {
                binaryData = bytes
                mimeType = if (bytes.size > 3 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()) "image/png" else "image/jpeg"
                pictureType = 3
            }
            tag.deleteArtworkField()
            tag.setField(artwork)
        }
        audio.commit()
    }
}
