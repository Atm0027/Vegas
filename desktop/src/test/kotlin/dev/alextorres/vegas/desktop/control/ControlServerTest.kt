package dev.alextorres.vegas.desktop.control

import dev.alextorres.vegas.desktop.playback.RepeatMode
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ControlServerTest {
    private val token = "a".repeat(64)
    private val logFile = Files.createTempFile("control", ".log")
    private val fake = FakeControl()

    private class FakeControl : VegasControl {
        val calls = mutableListOf<String>()
        var playing = true
        var hasSong = true

        private fun need() {
            if (!hasSong) throw ControlException("nada_sonando", "No está sonando nada", 409)
        }

        override fun state() = StateDto(
            reproduciendo = playing,
            cancion = if (hasSong) SongDto("abc", "Bohemian Rhapsody", "Queen", "A Night at the Opera", 354.0) else null,
            posicion = 12.5,
            duracion = 354.0,
            volumen = 0.8,
            aleatorio = false,
            repeticion = "no",
            cola = QueueSummary(3, 0),
            lista = OriginDto("l1", "Rock"),
        )

        override suspend fun play(query: String, type: PlayType): PlayedDto {
            calls += "play:$query:${type.wire}"
            if (query == "nada") throw ControlException("sin_resultados", "No he encontrado nada", 404)
            return PlayedDto(tipo = "cancion", titulo = query, artista = "X", id = "id1", canciones = 1)
        }

        override suspend fun search(query: String, type: PlayType) = listOf(SearchHitDto(type.wire, "id1", query, "X")).also { calls += "search:$query:${type.wire}" }

        override suspend fun pause() { need(); calls += "pause" }
        override suspend fun resume() { need(); calls += "resume" }
        override suspend fun toggle() { need(); calls += "toggle" }
        override suspend fun next() { need(); calls += "next" }
        override suspend fun previous() { need(); calls += "previous" }
        override suspend fun seek(seconds: Double) { need(); calls += "seek:$seconds" }
        override suspend fun setVolume(value: Double) { calls += "volume:$value" }
        override suspend fun setShuffle(on: Boolean) { calls += "shuffle:$on" }
        override suspend fun setRepeat(mode: RepeatMode) { calls += "repeat:$mode" }

        override fun queue() = QueueDto(posicion = 0, canciones = listOf(QueueItemDto(0, "abc", "T", "A", null, 1.0)))

        override suspend fun enqueue(query: String?, songId: String?, where: QueueWhere): SongDto {
            calls += "enqueue:$query:$songId:${where.wire}"
            return SongDto(songId ?: "q", "T", "A", null, null)
        }

        override suspend fun removeFromQueue(index: Int) {
            if (index == 0) throw ControlException("indice_actual", "No se puede quitar la canción que está sonando", 409)
            calls += "remove:$index"
        }

        override suspend fun clearQueue() { calls += "clear" }

        override suspend fun playlists() = listOf(PlaylistDto("l1", "Rock", 10, true))

        override suspend fun playPlaylist(id: String, shuffle: Boolean): PlayedDto {
            if (id != "l1") throw ControlException("lista_no_encontrada", "No existe esa lista", 404)
            calls += "playlist:$id:$shuffle"
            return PlayedDto(tipo = "lista", titulo = "Rock", artista = null, id = id, canciones = 10)
        }

        override suspend fun addToPlaylist(playlistId: String, songId: String?): AddedDto {
            calls += "add:$playlistId:$songId"
            return AddedDto(cancion = SongDto(songId ?: "abc", "T", "A", null, null), lista = OriginDto(playlistId, "Rock"), duplicada = false)
        }

        override suspend fun show() { calls += "show" }
    }

    private fun ApplicationTestBuilder.setUp(limiter: RateLimiter = RateLimiter(), remoteHost: String = "127.0.0.1") {
        application { controlModule(ControlServerDeps(fake, token, log = ControlLog(logFile), limiter = limiter, remoteHostOf = { remoteHost })) }
    }

    private fun ApplicationTestBuilder.client(): HttpClient = createClient { }

    private suspend fun HttpClient.call(
        method: String,
        path: String,
        body: String? = null,
        auth: String? = token,
        host: String = "127.0.0.1:$CONTROL_PORT",
        extra: Map<String, String> = emptyMap(),
    ): HttpResponse {
        val build: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            header(HttpHeaders.Host, host)
            auth?.let { bearerAuth(it) }
            extra.forEach { (k, v) -> header(k, v) }
            body?.let {
                contentType(ContentType.Application.Json)
                setBody(it)
            }
        }
        return when (method) {
            "GET" -> get(path, build)
            "DELETE" -> delete(path, build)
            else -> post(path, build)
        }
    }

    private suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpResponse.errorCode() = json()["error"]!!.jsonPrimitive.content

    @Test
    fun healthNeedsNoTokenAndRevealsNothingElse() = testApplication {
        setUp()
        val response = client().call("GET", "/v1/salud", auth = null)
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"vegas":true}""", response.bodyAsText())
    }

    @Test
    fun missingOrWrongTokenIs401AndLogged() = testApplication {
        setUp()
        val c = client()
        val none = c.call("GET", "/v1/estado", auth = null)
        assertEquals(HttpStatusCode.Unauthorized, none.status)
        assertEquals("token", none.errorCode())
        assertFalse(none.bodyAsText().contains("Queen"))
        assertEquals(HttpStatusCode.Unauthorized, c.call("GET", "/v1/estado", auth = "b".repeat(64)).status)
        assertEquals(HttpStatusCode.Unauthorized, c.call("GET", "/v1/estado", auth = token.dropLast(1)).status)
        assertEquals(HttpStatusCode.Unauthorized, c.call("POST", "/v1/pausa", auth = null).status)
        assertTrue(fake.calls.isEmpty())
        val log = Files.readString(logFile)
        assertTrue(log.contains("Token no válido"))
        assertFalse(log.contains("bbbb"))
    }

    @Test
    fun wrongHostIsRejectedEvenWithToken() = testApplication {
        setUp()
        val c = client()
        for (host in listOf("evil.example:$CONTROL_PORT", "127.0.0.1", "localhost:80", "127.0.0.1:47321")) {
            val r = c.call("GET", "/v1/estado", host = host)
            assertEquals(host, HttpStatusCode.Forbidden, r.status)
            assertEquals("host", r.errorCode())
        }
        assertEquals(HttpStatusCode.OK, c.call("GET", "/v1/estado", host = "localhost:$CONTROL_PORT").status)
        assertEquals(HttpStatusCode.Forbidden, c.call("GET", "/v1/salud", auth = null, host = "evil.example").status)
    }

    @Test
    fun webPagesAreRefusedAndGetNoCorsHeaders() = testApplication {
        setUp()
        val r = client().call("GET", "/v1/estado", extra = mapOf(HttpHeaders.Origin to "http://evil.example"))
        assertEquals(HttpStatusCode.Forbidden, r.status)
        assertEquals("origen", r.errorCode())
        val ok = client().call("GET", "/v1/estado")
        assertNull(ok.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    @Test
    fun nonLoopbackCallerIsRejected() = testApplication {
        setUp(remoteHost = "192.168.1.20")
        assertEquals(HttpStatusCode.Forbidden, client().call("GET", "/v1/salud", auth = null).status)
    }

    @Test
    fun largeBodyIsRejected() = testApplication {
        setUp()
        val big = """{"consulta":"${"x".repeat(MAX_BODY_BYTES)}"}"""
        val r = client().call("POST", "/v1/reproducir", big)
        assertEquals(HttpStatusCode.PayloadTooLarge, r.status)
        assertEquals("cuerpo_grande", r.errorCode())
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun rateLimitAnswers429() = testApplication {
        setUp(limiter = RateLimiter(capacity = 2.0, perSecond = 0.0))
        val c = client()
        assertEquals(HttpStatusCode.OK, c.call("GET", "/v1/estado").status)
        assertEquals(HttpStatusCode.OK, c.call("GET", "/v1/estado").status)
        val r = c.call("GET", "/v1/estado")
        assertEquals(HttpStatusCode.TooManyRequests, r.status)
        assertEquals("tasa", r.errorCode())
    }

    @Test
    fun stateHasEverythingTheNowPanelNeeds() = testApplication {
        setUp()
        val s = client().call("GET", "/v1/estado").json()
        assertTrue(s["ok"]!!.jsonPrimitive.boolean)
        assertTrue(s["reproduciendo"]!!.jsonPrimitive.boolean)
        val song = s["cancion"]!!.jsonObject
        assertEquals("Bohemian Rhapsody", song["titulo"]!!.jsonPrimitive.content)
        assertEquals("Queen", song["artista"]!!.jsonPrimitive.content)
        assertEquals("abc", song["id"]!!.jsonPrimitive.content)
        assertEquals(12.5, s["posicion"]!!.jsonPrimitive.double, 0.0)
        assertEquals(0.8, s["volumen"]!!.jsonPrimitive.double, 0.0)
        assertEquals("no", s["repeticion"]!!.jsonPrimitive.content)
        assertEquals(3, s["cola"]!!.jsonObject["total"]!!.jsonPrimitive.int)
        assertEquals("Rock", s["lista"]!!.jsonObject["nombre"]!!.jsonPrimitive.content)
    }

    @Test
    fun playAndErrors() = testApplication {
        setUp()
        val c = client()
        val ok = c.call("POST", "/v1/reproducir", """{"consulta":"Queen","tipo":"artista"}""")
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals("Queen", ok.json()["titulo"]!!.jsonPrimitive.content)
        assertEquals("play:Queen:artista", fake.calls.last())
        c.call("POST", "/v1/reproducir", """{"consulta":"algo"}""")
        assertEquals("play:algo:auto", fake.calls.last())

        val none = c.call("POST", "/v1/reproducir", """{"consulta":"nada","tipo":"cancion"}""")
        assertEquals(HttpStatusCode.NotFound, none.status)
        assertFalse(none.json()["ok"]!!.jsonPrimitive.boolean)
        assertEquals("sin_resultados", none.errorCode())

        assertEquals("tipo", c.call("POST", "/v1/reproducir", """{"consulta":"x","tipo":"podcast"}""").errorCode())
        assertEquals("peticion", c.call("POST", "/v1/reproducir", """{"tipo":"cancion"}""").errorCode())
        assertEquals("peticion", c.call("POST", "/v1/reproducir", """[1,2]""").errorCode())
        assertEquals("peticion", c.call("POST", "/v1/reproducir", """no es json""").errorCode())
    }

    @Test
    fun bodyMustBeJson() = testApplication {
        setUp()
        val r = client().post("/v1/reproducir") {
            header(HttpHeaders.Host, "127.0.0.1:$CONTROL_PORT")
            bearerAuth(token)
            contentType(ContentType.Text.Plain)
            setBody("""{"consulta":"x"}""")
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType, r.status)
        assertEquals("tipo_contenido", r.errorCode())
    }

    @Test
    fun search() = testApplication {
        setUp()
        val c = client()
        val r = c.call("GET", "/v1/buscar?q=Queen&tipo=album").json()
        assertEquals("album", r["resultados"]!!.jsonArray[0].jsonObject["tipo"]!!.jsonPrimitive.content)
        assertEquals("search:Queen:album", fake.calls.last())
        assertEquals("peticion", c.call("GET", "/v1/buscar").errorCode())
    }

    @Test
    fun transportCommandsAnswerWithState() = testApplication {
        setUp()
        val c = client()
        for ((path, call) in listOf("pausa" to "pause", "reanudar" to "resume", "alternar" to "toggle", "siguiente" to "next", "anterior" to "previous")) {
            val r = c.call("POST", "/v1/$path")
            assertEquals(HttpStatusCode.OK, r.status)
            assertEquals(call, fake.calls.last())
            assertEquals("Queen", r.json()["cancion"]!!.jsonObject["artista"]!!.jsonPrimitive.content)
        }
        fake.hasSong = false
        val r = c.call("POST", "/v1/siguiente")
        assertEquals(HttpStatusCode.Conflict, r.status)
        assertEquals("nada_sonando", r.errorCode())
    }

    @Test
    fun explicitValues() = testApplication {
        setUp()
        val c = client()
        c.call("POST", "/v1/posicion", """{"segundos":30}""")
        assertEquals("seek:30.0", fake.calls.last())
        c.call("POST", "/v1/volumen", """{"valor":0.25}""")
        assertEquals("volume:0.25", fake.calls.last())
        c.call("POST", "/v1/aleatorio", """{"activo":true}""")
        assertEquals("shuffle:true", fake.calls.last())
        c.call("POST", "/v1/repetir", """{"modo":"una"}""")
        assertEquals("repeat:ONE", fake.calls.last())
        c.call("POST", "/v1/repetir", """{"modo":"lista"}""")
        assertEquals("repeat:ALL", fake.calls.last())

        val count = fake.calls.size
        assertEquals("valor", c.call("POST", "/v1/volumen", """{"valor":1.5}""").errorCode())
        assertEquals("peticion", c.call("POST", "/v1/volumen", """{"valor":"alto"}""").errorCode())
        assertEquals("valor", c.call("POST", "/v1/posicion", """{"segundos":-1}""").errorCode())
        assertEquals("peticion", c.call("POST", "/v1/aleatorio", """{}""").errorCode())
        assertEquals("peticion", c.call("POST", "/v1/aleatorio", """{"activo":"si"}""").errorCode())
        assertEquals("valor", c.call("POST", "/v1/repetir", """{"modo":"siempre"}""").errorCode())
        assertEquals(count, fake.calls.size)
    }

    @Test
    fun queueEndpoints() = testApplication {
        setUp()
        val c = client()
        val q = c.call("GET", "/v1/cola").json()
        assertEquals(1, q["canciones"]!!.jsonArray.size)
        c.call("POST", "/v1/cola", """{"consulta":"Imagine","donde":"siguiente"}""")
        assertEquals("enqueue:Imagine:null:siguiente", fake.calls.last())
        c.call("POST", "/v1/cola", """{"id":"xyz"}""")
        assertEquals("enqueue:null:xyz:final", fake.calls.last())
        assertEquals("peticion", c.call("POST", "/v1/cola", """{"donde":"final"}""").errorCode())
        assertEquals("donde", c.call("POST", "/v1/cola", """{"id":"x","donde":"principio"}""").errorCode())

        assertEquals(HttpStatusCode.OK, c.call("DELETE", "/v1/cola/2").status)
        assertEquals("remove:2", fake.calls.last())
        assertEquals("indice_actual", c.call("DELETE", "/v1/cola/0").errorCode())
        assertEquals("indice", c.call("DELETE", "/v1/cola/dos").errorCode())
        c.call("POST", "/v1/cola/vaciar")
        assertEquals("clear", fake.calls.last())
    }

    @Test
    fun playlistEndpoints() = testApplication {
        setUp()
        val c = client()
        val l = c.call("GET", "/v1/listas").json()["listas"]!!.jsonArray[0].jsonObject
        assertEquals("Rock", l["nombre"]!!.jsonPrimitive.content)
        assertTrue(l["fijada"]!!.jsonPrimitive.boolean)
        c.call("POST", "/v1/listas/l1/reproducir", """{"aleatorio":true}""")
        assertEquals("playlist:l1:true", fake.calls.last())
        c.call("POST", "/v1/listas/l1/reproducir")
        assertEquals("playlist:l1:false", fake.calls.last())
        assertEquals("lista_no_encontrada", c.call("POST", "/v1/listas/otra/reproducir").errorCode())

        c.call("POST", "/v1/listas/l1/anadir")
        assertEquals("add:l1:null", fake.calls.last())
        c.call("POST", "/v1/listas/l1/a%C3%B1adir", """{"id":"xyz"}""")
        assertEquals("add:l1:xyz", fake.calls.last())
    }

    @Test
    fun showAndUnknownRoutes() = testApplication {
        setUp()
        val c = client()
        c.call("POST", "/v1/mostrar")
        assertEquals("show", fake.calls.last())
        val r = c.call("GET", "/v1/pausa")
        assertEquals(HttpStatusCode.NotFound, r.status)
        assertEquals("ruta", r.errorCode())
        assertEquals(HttpStatusCode.Unauthorized, c.call("GET", "/cualquier", auth = null).status)
    }

    @Test
    fun tokenIsCreatedOnceWith256Bits() {
        val dir = Files.createTempDirectory("vegas")
        val file = dir.resolve("control.token")
        val first = ControlToken.loadOrCreate(file)
        assertTrue(Regex("^[0-9a-f]{64}$").matches(first))
        assertEquals(first, ControlToken.loadOrCreate(file))
        Files.writeString(file, "roto")
        assertTrue(ControlToken.loadOrCreate(file) != first)
        val perms = runCatching { Files.getPosixFilePermissions(file) }.getOrNull()
        if (perms != null) assertEquals(setOf(java.nio.file.attribute.PosixFilePermission.OWNER_READ, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE), perms)
    }
}
