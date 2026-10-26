package dev.alextorres.vegas.desktop.control

import dev.alextorres.vegas.desktop.db.Library
import dev.alextorres.vegas.desktop.db.openInMemoryDatabase
import dev.alextorres.vegas.desktop.playback.AudioSource
import dev.alextorres.vegas.desktop.playback.EngineState
import dev.alextorres.vegas.desktop.playback.PlaybackEngine
import dev.alextorres.vegas.desktop.playback.PlayerController
import dev.alextorres.vegas.desktop.playback.QueueStore
import dev.alextorres.vegas.desktop.playback.RepeatMode
import dev.alextorres.vegas.desktop.playback.ResolvedStream
import dev.alextorres.vegas.desktop.playback.StreamResolver
import dev.alextorres.vegas.desktop.playback.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.file.Files

class DesktopControlTest {
    private class FakeEngine : PlaybackEngine {
        override val state = MutableStateFlow(EngineState.IDLE)
        override val positionMs = MutableStateFlow(0L)
        override val durationMs = MutableStateFlow(0L)
        override val errors = MutableSharedFlow<Throwable>(extraBufferCapacity = 8)
        override var volume = 1f

        override fun load(source: AudioSource, startMs: Long, playWhenReady: Boolean) {
            positionMs.value = startMs
            durationMs.value = 0
            state.value = if (playWhenReady) EngineState.PLAYING else EngineState.PAUSED
        }
        override fun play() { state.value = EngineState.PLAYING }
        override fun pause() { state.value = EngineState.PAUSED }
        override fun seekTo(ms: Long) { positionMs.value = ms }
        override fun release() { state.value = EngineState.IDLE }
    }

    private fun track(id: String) = Track(id, "Song $id", "Artist $id", "Album", null, 180_000)

    private val catalog = object : Catalog {
        override suspend fun search(query: String, type: PlayType): List<CatalogItem> =
            when {
                query == "nada" -> emptyList()
                type == PlayType.ARTIST -> listOf(CatalogItem(PlayType.ARTIST, "UCqueen", "Queen", null))
                else -> listOf(CatalogItem(PlayType.SONG, "s-$query", "Song s-$query", "Artist", track("s-$query")))
            }

        override suspend fun tracksOf(item: CatalogItem) =
            if (item.type == PlayType.ARTIST) listOf(track("q1"), track("q2"), track("q3")) else listOfNotNull(item.track)

        override suspend fun song(id: String) = if (id == "online") track("online") else null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val queueFile = Files.createTempFile("queue", ".json").also { Files.delete(it) }
    private val engine = FakeEngine()
    private val controller = PlayerController(
        scope = scope,
        resolver = StreamResolver(extract = { id, _ -> ResolvedStream(id, "https://x/$id", emptyMap(), "audio/webm", Long.MAX_VALUE, "VISIONOS", null) }),
        engine = engine,
        related = { emptyList() },
        store = QueueStore(queueFile),
    )
    private val db = openInMemoryDatabase()
    private val library = Library(db)
    private var volume = 1f
    private var shown = 0
    private val control = DesktopControl(controller, library, catalog, { volume }, { volume = it }, { shown++ }, Dispatchers.Unconfined)

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        Files.deleteIfExists(queueFile)
    }

    private suspend fun expectError(code: String, block: suspend () -> Unit) {
        try {
            block()
            fail("expected $code")
        } catch (e: ControlException) {
            assertEquals(code, e.code)
        }
    }

    @Test
    fun emptyStateAndNothingPlaying() = runBlocking {
        val s = control.state()
        assertNull(s.cancion)
        assertFalse(s.reproduciendo)
        assertEquals(-1, s.cola.posicion)
        expectError("nada_sonando") { control.pause() }
        expectError("nada_sonando") { control.next() }
        expectError("nada_sonando") { control.addToPlaylist(library.createPlaylist("Rock"), null) }
    }

    @Test
    fun playArtistQueuesTopSongs() = runBlocking {
        val played = control.play("queen", PlayType.ARTIST)
        assertEquals("artista", played.tipo)
        assertEquals(3, played.canciones)
        val s = control.state()
        assertTrue(s.reproduciendo)
        assertEquals("q1", s.cancion?.id)
        assertEquals(3, s.cola.total)
        assertNull(s.lista)
        expectError("sin_resultados") { control.play("nada", PlayType.SONG) }
    }

    @Test
    fun playlistByApproximateNameRemembersOrigin() = runBlocking {
        val rock = library.createPlaylist("Rock de los 80")
        library.addTracks(rock, listOf(track("a"), track("b")))
        library.createPlaylist("Jazz")
        val played = control.play("rock de los ochenta", PlayType.PLAYLIST)
        assertEquals("lista", played.tipo)
        assertEquals(rock, played.id)
        assertEquals(OriginDto(rock, "Rock de los 80"), control.state().lista)
        expectError("sin_resultados") { control.play("reguetón", PlayType.PLAYLIST) }
        // auto only takes an own playlist on a near-exact name; otherwise it goes to the catalogue.
        assertEquals("lista", control.play("ROCK DE LOS 80", PlayType.AUTO).tipo)
        assertEquals("cancion", control.play("rock", PlayType.AUTO).tipo)
        val empty = library.createPlaylist("Vacía")
        expectError("lista_vacia") { control.playPlaylist(empty, false) }
        expectError("lista_no_encontrada") { control.playPlaylist("no-existe", false) }
    }

    @Test
    fun explicitControlsAreIdempotent() = runBlocking {
        control.play("x", PlayType.SONG)
        control.pause()
        control.pause()
        assertFalse(control.state().reproduciendo)
        control.resume()
        control.resume()
        assertTrue(control.state().reproduciendo)
        control.setRepeat(RepeatMode.ONE)
        control.setRepeat(RepeatMode.ONE)
        assertEquals("una", control.state().repeticion)
        control.setVolume(0.3)
        assertEquals(0.3, control.state().volumen, 1e-6)
        control.seek(500.0)
        assertEquals(180.0, control.state().posicion, 0.0)
        control.show()
        assertEquals(1, shown)
    }

    @Test
    fun nextAnswersWithTheNewSongsPositionAndLength() = runBlocking {
        control.play("queen", PlayType.ARTIST)
        engine.positionMs.value = 90_000
        engine.durationMs.value = 354_000
        control.next()
        val s = control.state()
        assertEquals("q2", s.cancion?.id)
        assertEquals(180.0, s.duracion, 0.0)
        assertEquals(0.0, s.posicion, 0.0)
    }

    @Test
    fun queueEditing() = runBlocking {
        control.play("x", PlayType.SONG)
        control.enqueue("y", null, QueueWhere.END)
        control.enqueue("z", null, QueueWhere.NEXT)
        control.enqueue(null, "online", QueueWhere.END)
        assertEquals(listOf("s-x", "s-z", "s-y", "online"), control.queue().canciones.map { it.id })
        expectError("sin_resultados") { control.enqueue(null, "desconocida", QueueWhere.END) }
        expectError("indice_actual") { control.removeFromQueue(0) }
        expectError("indice") { control.removeFromQueue(9) }
        control.removeFromQueue(1)
        assertEquals(listOf("s-x", "s-y", "online"), control.queue().canciones.map { it.id })
        control.clearQueue()
        assertEquals(listOf("s-x"), control.queue().canciones.map { it.id })
    }

    @Test
    fun addCurrentSongToPlaylistOnce() = runBlocking {
        val rock = library.createPlaylist("Rock")
        control.play("x", PlayType.SONG)
        assertFalse(control.addToPlaylist(rock, null).duplicada)
        assertTrue(control.addToPlaylist(rock, null).duplicada)
        assertFalse(control.addToPlaylist(rock, "online").duplicada)
        assertEquals(listOf("s-x", "online"), library.entries(rock).first().map { it.song.id })
        assertEquals(2, control.playlists().single().canciones)
    }

    @Test
    fun matcherIgnoresCaseAndAccents() {
        assertEquals(1.0, PlaylistMatcher.score("cancion triste", "Canción Triste!"), 0.0)
        assertTrue(PlaylistMatcher.score("gym", "Gym 2026") >= PlaylistMatcher.LOOSE)
        assertTrue(PlaylistMatcher.score("jazz", "Rock") < PlaylistMatcher.LOOSE)
    }
}
