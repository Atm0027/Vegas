/**
 * Vegas desktop: the control API JARVIS uses, on 127.0.0.1 only. Every other local process is treated as
 * a possible attacker: a bearer token checked in constant time, a strict Host header against DNS
 * rebinding, no CORS (and requests from web pages refused), small bodies and a rate limit.
 * Separate from the sync server: it never syncs and never sees the pairing tokens.
 * Licensed under GPL-3.0
 */

package dev.alextorres.vegas.desktop.control

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.origin
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.net.InetAddress
import java.security.MessageDigest

class ControlServerDeps(
    val control: VegasControl,
    val token: String,
    val port: Int = CONTROL_PORT,
    val log: ControlLog = ControlLog.NONE,
    val limiter: RateLimiter = RateLimiter(),
    val remoteHostOf: (ApplicationCall) -> String = { it.request.origin.remoteAddress },
)

private val json = Json { encodeDefaults = true; explicitNulls = true }

fun Application.controlModule(deps: ControlServerDeps) {
    val allowedHosts = setOf("127.0.0.1:${deps.port}", "localhost:${deps.port}")
    val tokenBytes = deps.token.toByteArray()
    install(
        createApplicationPlugin("ControlGuard") {
            onCall { call ->
                when {
                    !isLoopback(deps.remoteHostOf(call)) -> call.fail(HttpStatusCode.Forbidden, "origen", "Solo desde este equipo")
                    call.request.headers[HttpHeaders.Host]?.lowercase() !in allowedHosts ->
                        call.fail(HttpStatusCode.Forbidden, "host", "Host no permitido")
                    // Browsers always send Origin on cross-site requests; JARVIS never does.
                    call.request.headers[HttpHeaders.Origin] != null -> call.fail(HttpStatusCode.Forbidden, "origen", "No se acepta desde páginas web")
                    !deps.limiter.tryAcquire() -> call.fail(HttpStatusCode.TooManyRequests, "tasa", "Demasiadas peticiones")
                    (call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0) > MAX_BODY_BYTES ->
                        call.fail(HttpStatusCode.PayloadTooLarge, "cuerpo_grande", "Cuerpo demasiado grande")
                    call.request.local.uri.substringBefore('?') == HEALTH_PATH -> Unit
                    !validToken(call.request.headers[HttpHeaders.Authorization], tokenBytes) -> {
                        deps.log.authFailure(call.request.local.method.value, call.request.local.uri.substringBefore('?'))
                        call.fail(HttpStatusCode.Unauthorized, "token", "No autorizado")
                    }
                }
            }
        },
    )
    val c = deps.control
    routing {
        get(HEALTH_PATH) { call.respondText(json.encodeToString(HealthDto.serializer(), HealthDto()), ContentType.Application.Json) }
        route("/v1") {
            get("/estado") { reply(StateDto.serializer()) { c.state() } }
            post("/reproducir") {
                reply(PlayedDto.serializer()) {
                    val body = body()
                    c.play(body.requiredString("consulta"), PlayType.parse(body.string("tipo")))
                }
            }
            get("/buscar") {
                reply(SearchDto.serializer()) {
                    val q = call.request.queryParameters["q"]?.takeIf { it.isNotBlank() }
                        ?: throw ControlException("peticion", "Falta «q»")
                    if (q.length > MAX_TEXT) throw ControlException("peticion", "«q» es demasiado largo")
                    SearchDto(resultados = c.search(q, PlayType.parse(call.request.queryParameters["tipo"])))
                }
            }
            post("/pausa") { command(c) { c.pause() } }
            post("/reanudar") { command(c) { c.resume() } }
            post("/alternar") { command(c) { c.toggle() } }
            post("/siguiente") { command(c) { c.next() } }
            post("/anterior") { command(c) { c.previous() } }
            post("/posicion") {
                command(c) {
                    val seconds = body().requiredDouble("segundos")
                    if (seconds < 0) throw ControlException("valor", "«segundos» no puede ser negativo")
                    c.seek(seconds)
                }
            }
            post("/volumen") {
                command(c) {
                    val value = body().requiredDouble("valor")
                    if (value !in 0.0..1.0) throw ControlException("valor", "«valor» tiene que estar entre 0 y 1")
                    c.setVolume(value)
                }
            }
            post("/aleatorio") { command(c) { c.setShuffle(body().requiredBoolean("activo")) } }
            post("/repetir") { command(c) { c.setRepeat(RepeatWire.parse(body().requiredString("modo"))) } }
            get("/cola") { reply(QueueDto.serializer()) { c.queue() } }
            post("/cola") {
                reply(QueuedDto.serializer()) {
                    val body = body()
                    val query = body.string("consulta")
                    val id = body.string("id")
                    if (query.isNullOrBlank() && id.isNullOrBlank()) throw ControlException("peticion", "Falta «consulta» o «id»")
                    val where = QueueWhere.parse(body.string("donde"))
                    QueuedDto(cancion = c.enqueue(query, id, where), donde = where.wire)
                }
            }
            post("/cola/vaciar") { command(c) { c.clearQueue() } }
            delete("/cola/{indice}") {
                command(c) {
                    val index = call.parameters["indice"]?.toIntOrNull() ?: throw ControlException("indice", "Índice no válido")
                    c.removeFromQueue(index)
                }
            }
            get("/listas") { reply(PlaylistsDto.serializer()) { PlaylistsDto(listas = c.playlists()) } }
            post("/listas/{id}/reproducir") {
                reply(PlayedDto.serializer()) { c.playPlaylist(call.parameters["id"].orEmpty(), body().boolean("aleatorio") ?: false) }
            }
            // "añadir" as asked, and "anadir" so clients never have to encode the ñ.
            for (path in listOf("/listas/{id}/anadir", "/listas/{id}/añadir")) {
                post(path) { reply(AddedDto.serializer()) { c.addToPlaylist(call.parameters["id"].orEmpty(), body().string("id")) } }
            }
            post("/mostrar") { command(c) { c.show() } }
        }
        route("{...}") {
            handle { call.fail(HttpStatusCode.NotFound, "ruta", "No existe esa ruta") }
        }
    }
}

private fun isLoopback(host: String): Boolean =
    host == "localhost" || runCatching { InetAddress.getByName(host).isLoopbackAddress }.getOrDefault(false)

private fun validToken(header: String?, expected: ByteArray): Boolean {
    if (header == null || !header.startsWith("Bearer ")) return false
    return MessageDigest.isEqual(header.removePrefix("Bearer ").trim().toByteArray(), expected)
}

private suspend fun ApplicationCall.fail(status: HttpStatusCode, code: String, message: String) {
    response.header(HttpHeaders.CacheControl, "no-store")
    respondText(json.encodeToString(ErrorDto.serializer(), ErrorDto(error = code, mensaje = message)), ContentType.Application.Json, status)
}

private suspend fun <T> RoutingContext.reply(serializer: KSerializer<T>, block: suspend RoutingContext.() -> T) {
    val result = try {
        block()
    } catch (e: ControlException) {
        return call.fail(HttpStatusCode.fromValue(e.status), e.code, e.message)
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        return call.fail(HttpStatusCode.InternalServerError, "interno", "Error interno de Vegas")
    }
    call.response.header(HttpHeaders.CacheControl, "no-store")
    call.respondText(json.encodeToString(serializer, result), ContentType.Application.Json)
}

/** Runs a command and answers with the state right after it, so JARVIS needs no second call. */
private suspend fun RoutingContext.command(control: VegasControl, block: suspend RoutingContext.() -> Unit) {
    reply(StateDto.serializer()) {
        block()
        control.state()
    }
}

/** Reads a small JSON object; an empty body counts as {}. */
private suspend fun RoutingContext.body(): JsonObject {
    val bytes = call.receiveChannel().readRemaining(MAX_BODY_BYTES + 1L).readByteArray()
    if (bytes.size > MAX_BODY_BYTES) throw ControlException("cuerpo_grande", "Cuerpo demasiado grande", 413)
    if (bytes.isEmpty()) return JsonObject(emptyMap())
    if (!call.request.contentType().match(ContentType.Application.Json)) {
        throw ControlException("tipo_contenido", "Usa Content-Type: application/json", 415)
    }
    return runCatching { json.parseToJsonElement(bytes.decodeToString()) as JsonObject }
        .getOrElse { throw ControlException("peticion", "El cuerpo no es un objeto JSON") }
}

private fun JsonObject.primitive(name: String): JsonPrimitive? =
    when (val value: JsonElement? = this[name]) {
        null, JsonNull -> null
        is JsonPrimitive -> value
        else -> throw ControlException("peticion", "«$name» no es válido")
    }

private fun JsonObject.string(name: String): String? =
    primitive(name)?.let {
        if (!it.isString) throw ControlException("peticion", "«$name» tiene que ser texto")
        if (it.content.length > MAX_TEXT) throw ControlException("peticion", "«$name» es demasiado largo")
        it.content.trim()
    }

private fun JsonObject.requiredString(name: String): String =
    string(name)?.takeIf { it.isNotEmpty() } ?: throw ControlException("peticion", "Falta «$name»")

private fun JsonObject.requiredDouble(name: String): Double =
    primitive(name)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
        ?: throw ControlException("peticion", "Falta «$name» o no es un número")

private fun JsonObject.boolean(name: String): Boolean? =
    primitive(name)?.let { p -> p.takeIf { !it.isString }?.booleanOrNull ?: throw ControlException("peticion", "«$name» tiene que ser true o false") }

private fun JsonObject.requiredBoolean(name: String): Boolean = boolean(name) ?: throw ControlException("peticion", "Falta «$name»")

/** Starts the control API on 127.0.0.1:[CONTROL_PORT]; null if the port is taken. */
fun startControlServer(deps: ControlServerDeps): EmbeddedServer<NettyApplicationEngine, *>? =
    runCatching {
        embeddedServer(Netty, port = deps.port, host = "127.0.0.1") { controlModule(deps) }.start(wait = false)
    }.onFailure { deps.log.write("No se pudo abrir 127.0.0.1:${deps.port}: ${it.message}") }.getOrNull()

/** A token bucket shared by every caller: JARVIS polls every few seconds, far below this. */
class RateLimiter(
    private val capacity: Double = 60.0,
    private val perSecond: Double = 30.0,
    private val now: () -> Long = System::nanoTime,
) {
    private var tokens = capacity
    private var last = now()

    @Synchronized
    fun tryAcquire(): Boolean {
        val t = now()
        tokens = minOf(capacity, tokens + (t - last) / 1e9 * perSecond)
        last = t
        if (tokens < 1) return false
        tokens -= 1
        return true
    }
}

const val CONTROL_PORT = 47_322
const val HEALTH_PATH = "/v1/salud"
const val MAX_BODY_BYTES = 16 * 1024
private const val MAX_TEXT = 500
