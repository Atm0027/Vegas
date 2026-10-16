/**
 * Vega: writes title, artists, album and cover into a downloaded m4a, so the file looks right in
 * the Music/Vega folder and in other players.
 * Licensed under GPL-3.0
 */

package com.metrolist.music.offline

import org.jaudiotagger.audio.AudioFileIO
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
    fun write(file: File, tags: SongTags) {
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
