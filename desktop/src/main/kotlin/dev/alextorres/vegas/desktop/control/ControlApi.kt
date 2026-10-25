/**
 * Vegas desktop: what JARVIS can ask of Vegas through the local control API, and the JSON it gets back.
 * The contract is documented in docs/vegas/control-jarvis.md; any change there must be told to JARVIS.
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.desktop.control

import dev.alextorres.vegas.desktop.playback.RepeatMode
import dev.alextorres.vegas.desktop.playback.Track
import kotlinx.serialization.Serializable

/** A failure with a stable [code] for JARVIS and a Spanish [message] for people. */
class ControlException(val code: String, override val message: String, val status: Int = 400) : Exception(message)

enum class PlayType(val wire: String) {
    SONG("cancion"),
    ARTIST("artista"),
    ALBUM("album"),
    PLAYLIST("lista"),
    AUTO("auto"),
    ;

    companion object {
        fun parse(value: String?): PlayType =
            if (value.isNullOrBlank()) AUTO else entries.firstOrNull { it.wire == value.trim().lowercase() }
                ?: throw ControlException("tipo", "Tipo no válido: usa cancion, artista, album, lista o auto")
    }
}

enum class QueueWhere(val wire: String) {
    NEXT("siguiente"),
    END("final"),
    ;

    companion object {
        fun parse(value: String?): QueueWhere =
            if (value.isNullOrBlank()) END else entries.firstOrNull { it.wire == value.trim().lowercase() }
                ?: throw ControlException("donde", "«donde» tiene que ser siguiente o final")
    }
}

object RepeatWire {
    fun of(mode: RepeatMode) = when (mode) {
        RepeatMode.OFF -> "no"
        RepeatMode.ALL -> "lista"
        RepeatMode.ONE -> "una"
    }

    fun parse(value: String?): RepeatMode =
        when (value?.trim()?.lowercase()) {
            "no" -> RepeatMode.OFF
            "lista" -> RepeatMode.ALL
            "una" -> RepeatMode.ONE
            else -> throw ControlException("valor", "«modo» tiene que ser no, lista o una")
        }
}

@Serializable
data class SongDto(val id: String, val titulo: String, val artista: String, val album: String?, val duracion: Double?)

fun Track.toDto() = SongDto(id, title, artists, album, durationMs?.let { it / 1000.0 })

@Serializable
data class QueueSummary(val total: Int, val posicion: Int)

@Serializable
data class OriginDto(val id: String, val nombre: String)

@Serializable
data class StateDto(
    val ok: Boolean = true,
    val reproduciendo: Boolean,
    val cancion: SongDto?,
    val posicion: Double,
    val duracion: Double,
    val volumen: Double,
    val aleatorio: Boolean,
    val repeticion: String,
    val cola: QueueSummary,
    val lista: OriginDto?,
)

@Serializable
data class PlayedDto(val ok: Boolean = true, val tipo: String, val titulo: String, val artista: String?, val id: String, val canciones: Int)

@Serializable
data class SearchHitDto(val tipo: String, val id: String, val titulo: String, val artista: String?, val album: String? = null, val duracion: Double? = null)

@Serializable
data class SearchDto(val ok: Boolean = true, val resultados: List<SearchHitDto>)

@Serializable
data class QueueItemDto(val indice: Int, val id: String, val titulo: String, val artista: String, val album: String?, val duracion: Double?)

@Serializable
data class QueueDto(val ok: Boolean = true, val posicion: Int, val canciones: List<QueueItemDto>)

@Serializable
data class QueuedDto(val ok: Boolean = true, val cancion: SongDto, val donde: String)

@Serializable
data class PlaylistDto(val id: String, val nombre: String, val canciones: Int, val fijada: Boolean)

@Serializable
data class PlaylistsDto(val ok: Boolean = true, val listas: List<PlaylistDto>)

@Serializable
data class AddedDto(val ok: Boolean = true, val cancion: SongDto, val lista: OriginDto, val duplicada: Boolean)

@Serializable
data class OkDto(val ok: Boolean = true)

@Serializable
data class ErrorDto(val ok: Boolean = false, val error: String, val mensaje: String)

@Serializable
data class HealthDto(val vegas: Boolean = true)

/** Everything the control API can do; the server only checks requests and turns these into JSON. */
interface VegasControl {
    fun state(): StateDto

    suspend fun play(query: String, type: PlayType): PlayedDto

    suspend fun search(query: String, type: PlayType): List<SearchHitDto>

    suspend fun pause()

    suspend fun resume()

    suspend fun toggle()

    suspend fun next()

    suspend fun previous()

    suspend fun seek(seconds: Double)

    suspend fun setVolume(value: Double)

    suspend fun setShuffle(on: Boolean)

    suspend fun setRepeat(mode: RepeatMode)

    fun queue(): QueueDto

    suspend fun enqueue(query: String?, songId: String?, where: QueueWhere): SongDto

    suspend fun removeFromQueue(index: Int)

    suspend fun clearQueue()

    suspend fun playlists(): List<PlaylistDto>

    suspend fun playPlaylist(id: String, shuffle: Boolean): PlayedDto

    suspend fun addToPlaylist(playlistId: String, songId: String?): AddedDto

    /** Brings the window to the front; used when Vegas is opened again while already running. */
    suspend fun show()
}
