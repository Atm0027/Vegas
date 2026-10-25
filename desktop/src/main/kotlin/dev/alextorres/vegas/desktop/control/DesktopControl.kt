/**
 * Vegas desktop: the control API's actions on the real player, library and YouTube Music catalogue.
 * Player calls hop to the UI thread, where the player lives.
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.desktop.control

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.models.filterExplicit
import com.metrolist.innertube.models.filterPodcasts
import com.metrolist.innertube.models.filterVideoSongs
import dev.alextorres.vegas.desktop.db.Library
import dev.alextorres.vegas.desktop.db.PlaylistSummary
import dev.alextorres.vegas.desktop.playback.PlayerController
import dev.alextorres.vegas.desktop.playback.QueueOrigin
import dev.alextorres.vegas.desktop.playback.RepeatMode
import dev.alextorres.vegas.desktop.playback.Track
import dev.alextorres.vegas.desktop.playback.toTrack
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.text.Normalizer

/** A song, artist or album found in YouTube Music. */
data class CatalogItem(val type: PlayType, val id: String, val title: String, val subtitle: String?, val track: Track? = null)

interface Catalog {
    /** [type] is cancion, artista, album or auto (the top results, in YouTube Music's order). */
    suspend fun search(query: String, type: PlayType): List<CatalogItem>

    /** What playing [item] means: the song, the artist's top songs or the album. */
    suspend fun tracksOf(item: CatalogItem): List<Track>

    suspend fun song(id: String): Track?
}

class DesktopControl(
    private val controller: PlayerController,
    private val library: Library,
    private val catalog: Catalog,
    private val volume: () -> Float,
    private val applyVolume: (Float) -> Unit,
    private val onShow: () -> Unit,
    private val main: CoroutineDispatcher,
) : VegasControl {
    override fun state(): StateDto {
        val s = controller.state.value
        val current = s.current
        return StateDto(
            reproduciendo = s.isPlaying,
            cancion = current?.toDto(),
            posicion = if (current == null) 0.0 else s.positionMs / 1000.0,
            duracion = (s.durationMs.takeIf { it > 0 } ?: current?.durationMs ?: 0) / 1000.0,
            volumen = volume().toDouble(),
            aleatorio = s.shuffle,
            repeticion = RepeatWire.of(s.repeat),
            cola = QueueSummary(s.queue.size, s.index),
            lista = s.origin?.let { OriginDto(it.playlistId, it.name) },
        )
    }

    override suspend fun play(query: String, type: PlayType): PlayedDto {
        if (type == PlayType.PLAYLIST || type == PlayType.AUTO) {
            val playlists = library.playlists().first()
            val minScore = if (type == PlayType.PLAYLIST) PlaylistMatcher.LOOSE else PlaylistMatcher.STRICT
            PlaylistMatcher.best(query, playlists, minScore)?.let { return playPlaylist(it.id, shuffle = false) }
            if (type == PlayType.PLAYLIST) throw ControlException("sin_resultados", "No hay ninguna lista parecida a «$query»", 404)
        }
        for (item in catalog.search(query, type).take(3)) {
            val tracks = catalog.tracksOf(item)
            if (tracks.isEmpty()) continue
            withContext(main) { controller.playQueue(tracks, 0) }
            return PlayedDto(tipo = item.type.wire, titulo = item.title, artista = item.subtitle, id = item.id, canciones = tracks.size)
        }
        throw ControlException("sin_resultados", "No he encontrado nada para «$query»", 404)
    }

    override suspend fun search(query: String, type: PlayType): List<SearchHitDto> {
        val own = if (type == PlayType.PLAYLIST || type == PlayType.AUTO) {
            val minScore = if (type == PlayType.PLAYLIST) PlaylistMatcher.LOOSE else PlaylistMatcher.STRICT
            PlaylistMatcher.ranked(query, library.playlists().first(), minScore).map { SearchHitDto("lista", it.id, it.name, null) }
        } else {
            emptyList()
        }
        if (type == PlayType.PLAYLIST) return own.take(MAX_RESULTS)
        val found = catalog.search(query, type).map { item ->
            SearchHitDto(item.type.wire, item.id, item.title, item.subtitle, item.track?.album, item.track?.durationMs?.let { it / 1000.0 })
        }
        return (own + found).take(MAX_RESULTS)
    }

    override suspend fun pause() = onCurrent { controller.pause() }

    override suspend fun resume() = onCurrent { controller.play() }

    override suspend fun toggle() = onCurrent { controller.togglePlay() }

    override suspend fun next() = onCurrent { controller.next() }

    override suspend fun previous() = onCurrent { controller.previous() }

    override suspend fun seek(seconds: Double) = onCurrent {
        val duration = state().duracion
        val target = if (duration > 0) seconds.coerceAtMost(duration) else seconds
        controller.seekTo((target * 1000).toLong())
    }

    override suspend fun setVolume(value: Double) = withContext(main) { applyVolume(value.toFloat()) }

    override suspend fun setShuffle(on: Boolean) = withContext(main) { controller.setShuffle(on) }

    override suspend fun setRepeat(mode: RepeatMode) = withContext(main) { controller.setRepeat(mode) }

    override fun queue(): QueueDto {
        val s = controller.state.value
        return QueueDto(
            posicion = s.index,
            canciones = s.queue.mapIndexed { i, t -> QueueItemDto(i, t.id, t.title, t.artists, t.album, t.durationMs?.let { it / 1000.0 }) },
        )
    }

    override suspend fun enqueue(query: String?, songId: String?, where: QueueWhere): SongDto {
        val track = if (!songId.isNullOrBlank()) {
            findSong(songId)
        } else {
            catalog.search(query.orEmpty(), PlayType.SONG).firstNotNullOfOrNull { it.track }
                ?: throw ControlException("sin_resultados", "No he encontrado ninguna canción para «$query»", 404)
        }
        withContext(main) {
            when (where) {
                QueueWhere.NEXT -> controller.playNext(track)
                QueueWhere.END -> controller.addToQueue(track)
            }
        }
        return track.toDto()
    }

    override suspend fun removeFromQueue(index: Int) = withContext(main) {
        val s = controller.state.value
        if (index !in s.queue.indices) throw ControlException("indice", "No hay ninguna canción en la posición $index de la cola", 404)
        if (index == s.index) throw ControlException("indice_actual", "No se puede quitar la canción que está sonando", 409)
        controller.removeFromQueue(index)
    }

    override suspend fun clearQueue() = withContext(main) { controller.clearQueue() }

    override suspend fun playlists(): List<PlaylistDto> =
        library.playlists().first().map { PlaylistDto(it.id, it.name, it.songCount, it.pinned) }

    override suspend fun playPlaylist(id: String, shuffle: Boolean): PlayedDto {
        val playlist = playlist(id)
        val tracks = library.entries(id).first().map { it.song.toTrack() }
        if (tracks.isEmpty()) throw ControlException("lista_vacia", "La lista «${playlist.name}» está vacía", 409)
        withContext(main) {
            controller.playQueue(tracks, if (shuffle) tracks.indices.random() else 0, shuffle, QueueOrigin(playlist.id, playlist.name))
        }
        return PlayedDto(tipo = PlayType.PLAYLIST.wire, titulo = playlist.name, artista = null, id = playlist.id, canciones = tracks.size)
    }

    override suspend fun addToPlaylist(playlistId: String, songId: String?): AddedDto {
        val playlist = playlist(playlistId)
        val track = if (!songId.isNullOrBlank()) {
            findSong(songId)
        } else {
            controller.state.value.current ?: throw ControlException("nada_sonando", "No está sonando nada", 409)
        }
        val result = library.addTracks(playlist.id, listOf(track))
        return AddedDto(cancion = track.toDto(), lista = OriginDto(playlist.id, playlist.name), duplicada = result.added == 0)
    }

    override suspend fun show() = withContext(main) { onShow() }

    private suspend fun playlist(id: String): PlaylistSummary =
        library.playlists().first().firstOrNull { it.id == id }
            ?: throw ControlException("lista_no_encontrada", "No existe esa lista", 404)

    private suspend fun findSong(id: String): Track =
        library.song(id) ?: controller.state.value.queue.firstOrNull { it.id == id } ?: catalog.song(id)
            ?: throw ControlException("sin_resultados", "No existe ninguna canción con id «$id»", 404)

    private suspend fun onCurrent(block: () -> Unit) = withContext(main) {
        if (controller.state.value.current == null) throw ControlException("nada_sonando", "No está sonando nada", 409)
        block()
    }

    private companion object {
        const val MAX_RESULTS = 10
    }
}

/** Finds an own playlist by a spoken, approximate name ("pon mi lista de rock" → «Rock 80s»). */
object PlaylistMatcher {
    /** For tipo=lista: the caller already said it is a playlist. */
    const val LOOSE = 0.6

    /** For tipo=auto: only a near-exact name wins over the catalogue. */
    const val STRICT = 0.9

    fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

    fun score(query: String, name: String): Double {
        val q = normalize(query)
        val n = normalize(name)
        if (q.isEmpty() || n.isEmpty()) return 0.0
        if (q == n) return 1.0
        val similarity = 1.0 - levenshtein(q, n).toDouble() / maxOf(q.length, n.length)
        val contained = if (n.contains(q) || q.contains(n)) 0.75 else 0.0
        return maxOf(similarity, contained)
    }

    fun ranked(query: String, playlists: List<PlaylistSummary>, minScore: Double): List<PlaylistSummary> =
        playlists.map { it to score(query, it.name) }.filter { it.second >= minScore }.sortedByDescending { it.second }.map { it.first }

    fun best(query: String, playlists: List<PlaylistSummary>, minScore: Double): PlaylistSummary? = ranked(query, playlists, minScore).firstOrNull()

    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val row = IntArray(b.length + 1)
            row[0] = i
            for (j in 1..b.length) {
                row[j] = minOf(previous[j] + 1, row[j - 1] + 1, previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            previous = row
        }
        return previous[b.length]
    }
}

/** YouTube Music through innertube, with the same filters the search screen applies. */
class YouTubeCatalog(
    private val hideExplicit: () -> Boolean,
    private val hideVideoSongs: () -> Boolean,
) : Catalog {
    override suspend fun search(query: String, type: PlayType): List<CatalogItem> {
        val items: List<YTItem> = when (type) {
            PlayType.SONG -> YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).orNetworkError().items
            PlayType.ARTIST -> YouTube.search(query, YouTube.SearchFilter.FILTER_ARTIST).orNetworkError().items
            PlayType.ALBUM -> YouTube.search(query, YouTube.SearchFilter.FILTER_ALBUM).orNetworkError().items
            PlayType.AUTO, PlayType.PLAYLIST -> YouTube.searchSummary(query).orNetworkError().summaries.flatMap { it.items }
        }
        return items.filterPodcasts().filterExplicit(hideExplicit()).filterVideoSongs(hideVideoSongs())
            .distinctBy { it.id }
            .mapNotNull { item ->
                when (item) {
                    is SongItem -> CatalogItem(PlayType.SONG, item.id, item.title, item.artists.joinToString(", ") { it.name }, item.toTrack())
                    is ArtistItem -> CatalogItem(PlayType.ARTIST, item.id, item.title, null)
                    is AlbumItem -> CatalogItem(PlayType.ALBUM, item.browseId, item.title, item.artists?.joinToString(", ") { it.name })
                    else -> null
                }
            }
    }

    override suspend fun tracksOf(item: CatalogItem): List<Track> =
        when (item.type) {
            PlayType.ARTIST -> {
                val page = YouTube.artist(item.id).orNetworkError()
                page.sections.map { it.items.filterPodcasts().filterExplicit(hideExplicit()).filterVideoSongs(hideVideoSongs()) }
                    .firstOrNull { items -> items.isNotEmpty() && items.all { it is SongItem } }
                    .orEmpty().filterIsInstance<SongItem>().map { it.toTrack() }
            }
            PlayType.ALBUM -> {
                val page = YouTube.album(item.id).orNetworkError()
                page.songs.map { it.toTrack().copy(album = page.album.title, thumbnailUrl = it.thumbnail.ifBlank { page.album.thumbnail }) }
            }
            else -> listOfNotNull(item.track)
        }

    override suspend fun song(id: String): Track? = YouTube.queue(listOf(id)).orNetworkError().firstOrNull()?.toTrack()

    private fun <T> Result<T>.orNetworkError(): T =
        getOrElse { throw ControlException("red", "No se pudo consultar YouTube Music", 502) }
}
