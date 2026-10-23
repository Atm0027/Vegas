/**
 * Vegas desktop: every user-visible text, in Spanish (personal app, no translations).
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.desktop.ui

object Strings {
    const val APP_NAME = "Vegas"
    const val HOME = "Inicio"
    const val SEARCH = "Buscar"
    const val LIBRARY = "Biblioteca"
    const val SETTINGS = "Ajustes"
    const val SEARCH_HINT = "Busca canciones, artistas, álbumes…"
    const val SONGS = "Canciones"
    const val ALBUMS = "Álbumes"
    const val ARTISTS = "Artistas"
    const val PLAYLISTS = "Listas"
    const val ALL = "Todo"
    const val PLAY = "Reproducir"
    const val PAUSE = "Pausa"
    const val SHUFFLE = "Aleatorio"
    const val NEXT = "Siguiente"
    const val PREVIOUS = "Anterior"
    const val REPEAT = "Repetir"
    const val QUEUE = "Cola"
    const val VOLUME = "Volumen"
    const val CLOSE_PLAYER = "Cerrar reproductor"
    const val PLAY_NEXT = "Reproducir siguiente"
    const val ADD_TO_QUEUE = "Añadir a la cola"
    const val ADD_TO_PLAYLIST = "Añadir a una lista…"
    const val REMOVE_FROM_PLAYLIST = "Quitar de la lista"
    const val REMOVE_FROM_QUEUE = "Quitar de la cola"
    const val NEW_PLAYLIST = "Nueva lista"
    const val PLAYLIST_NAME = "Nombre de la lista"
    const val RENAME = "Cambiar nombre"
    const val DELETE = "Eliminar"
    const val PIN = "Fijar"
    const val UNPIN = "Dejar de fijar"
    const val CANCEL = "Cancelar"
    const val SAVE = "Guardar"
    const val CREATE = "Crear"
    const val DONE = "Hecho"
    const val ADD = "Añadir"
    const val ALREADY_IN_PLAYLIST = "Ya está en la lista"
    const val ADD_ANYWAY = "Añadir igualmente"
    const val SKIP = "Omitir"
    const val NOTHING_PLAYING = "Nada en reproducción"
    const val EMPTY_LIBRARY = "Aún no tienes listas"
    const val EMPTY_PLAYLIST = "Esta lista está vacía"
    const val NO_RESULTS = "Sin resultados"
    const val LOAD_ERROR = "No se ha podido cargar. Comprueba la conexión."
    const val RETRY = "Reintentar"
    const val GRID = "Cuadrícula"
    const val LIST = "Lista"
    const val PURE_BLACK = "Negro puro"
    const val DYNAMIC_COLOR = "Color según la carátula"
    const val AUDIO_QUALITY = "Calidad de audio"
    const val QUALITY_HIGH = "Alta"
    const val QUALITY_LOW = "Baja"
    const val QUALITY_AUTO = "Automática"
    const val HIDE_EXPLICIT = "Ocultar contenido explícito"
    const val HIDE_VIDEO_SONGS = "Ocultar canciones de vídeo"

    /** YouTube sends search section titles in English; show them like the phone does. */
    fun searchSection(title: String) =
        when (title) {
            com.metrolist.innertube.YouTubeConstants.DEFAULT_TOP_RESULT -> "Mejor resultado"
            com.metrolist.innertube.YouTubeConstants.DEFAULT_OTHER_RESULTS -> "Otros"
            "Songs" -> SONGS
            "Videos" -> "Vídeos"
            "Albums" -> ALBUMS
            "Artists" -> ARTISTS
            "Playlists" -> PLAYLISTS
            "Profiles" -> "Perfiles"
            else -> title
        }

    const val PHONE_SYNC = "Vegas en el móvil"
    const val PAIR_PHONE = "Vincular un móvil"
    const val UNPAIR = "Desvincular"
    const val PAIR_EXPLAIN = "Vincula el móvil para que tus listas y ajustes se pongan al día solos cuando estéis en el mismo wifi de casa."
    const val PAIR_INSTRUCTIONS = "En el móvil: Ajustes → Vegas para PC → elige este PC y escribe el código."

    fun expiresIn(seconds: Long) = "Caduca en ${seconds / 60}:${"%02d".format(seconds % 60)}"

    fun lastSync(secondsAgo: Long?) =
        when {
            secondsAgo == null -> "Aún sin sincronizar"
            secondsAgo < 60 -> "Sincronizado hace un momento"
            secondsAgo < 3600 -> "Sincronizado hace ${secondsAgo / 60} min"
            else -> "Sincronizado hace ${secondsAgo / 3600} h"
        }

    fun songCount(n: Int) = if (n == 1) "1 canción" else "$n canciones"

    fun deleteConfirm(name: String) = "¿Eliminar la lista «$name»?"

    fun added(n: Int, playlist: String) = if (n == 1) "Añadida a «$playlist»" else "$n canciones añadidas a «$playlist»"
}
