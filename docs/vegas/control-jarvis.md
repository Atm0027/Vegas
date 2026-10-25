# Control de Vegas desde JARVIS

Contrato que JARVIS implementa tal cual. Cualquier cambio aquí hay que avisarlo a JARVIS.

- Diseño: `docs/superpowers/specs/2026-10-25-vegas-control-jarvis-design.md`.
- Código PC: `desktop/src/main/kotlin/dev/alextorres/vegas/desktop/control/`.
- Código móvil: `app/src/main/kotlin/com/metrolist/music/playback/JarvisControl.kt`.

## PC (Windows)

### Instalación y arranque

| Qué | Valor |
|---|---|
| Ejecutable | `%LOCALAPPDATA%\Vegas\Vegas.exe` |
| Arranque sin molestar | `Vegas.exe --silencioso` |
| Token | `%APPDATA%\Vegas\control.token` |
| Registro de rechazos | `%APPDATA%\Vegas\control.log` |
| Dirección | `http://127.0.0.1:47322` (solo 127.0.0.1, nunca la red) |

**`--silencioso`:**
- Vegas arranca sin ventana, con un icono en la bandeja («Abrir Vegas», «Salir»). No toma el foco.
- La música y la API funcionan igual con la ventana oculta.

**Instancia única:**
- Si Vegas ya está abierto, lanzarlo con `--silencioso` no hace nada (ya está listo).
- Lanzarlo sin argumentos muestra la ventana del que ya está abierto.

**Cuándo está listo:** JARVIS pregunta `GET /v1/salud` hasta recibir `200 {"vegas":true}`.

### Seguridad

- **Token:** 256 bits aleatorios en hexadecimal (64 caracteres). Se crea en el primer arranque y solo lo puede leer el usuario de Windows. Se manda en cada petición:
  `Authorization: Bearer <token>`.
- **Host:** tiene que ser exactamente `127.0.0.1:47322` o `localhost:47322`. Si no, 403 `host`.
- **Sin CORS:** no se envía ninguna cabecera CORS. Una petición con cabecera `Origin` (cualquier página web) recibe 403 `origen`.
- **Cuerpo:** JSON con `Content-Type: application/json`, como mucho 16 KB. Los textos, como mucho 500 caracteres.
- **Tasa:** 30 peticiones por segundo, con ráfagas de hasta 60. Si se pasa, 429 `tasa`.
- **Registro:** cada token no válido se apunta en `control.log`, sin el token. Si llegan muchos seguidos, se apunta una línea por segundo.
- **Separada de la sincronización:** esta API no sincroniza nada y no conoce los tokens de emparejamiento. El servidor de sincronización sigue en el puerto 47321.

### Respuestas

- Todas son JSON y llevan `ok`.
- Si algo falla: `{"ok": false, "error": "<código>", "mensaje": "<texto para personas>"}`.
- Las órdenes (pausa, siguiente, volumen…) responden con el estado justo después, igual que `GET /v1/estado`.
- Tiempos en segundos (número con decimales).
- Índices de la cola desde 0.

### Endpoints

| Método y ruta | Cuerpo / parámetros | Respuesta |
|---|---|---|
| `GET /v1/salud` | Sin token | `{"vegas":true}` |
| `GET /v1/estado` | | Estado (abajo) |
| `POST /v1/reproducir` | `{"consulta": "…", "tipo": "cancion\|artista\|album\|lista\|auto"}` (`tipo` por defecto `auto`) | Qué empezó a sonar |
| `GET /v1/buscar?q=…&tipo=…` | `tipo` como arriba, por defecto `auto` | `{"ok":true,"resultados":[…]}` (hasta 10) |
| `POST /v1/pausa` | | Estado |
| `POST /v1/reanudar` | | Estado |
| `POST /v1/alternar` | | Estado |
| `POST /v1/siguiente` | | Estado |
| `POST /v1/anterior` | | Estado |
| `POST /v1/posicion` | `{"segundos": 42.5}` | Estado |
| `POST /v1/volumen` | `{"valor": 0.5}` (0 a 1, absoluto) | Estado |
| `POST /v1/aleatorio` | `{"activo": true}` | Estado |
| `POST /v1/repetir` | `{"modo": "no\|lista\|una"}` | Estado |
| `GET /v1/cola` | | La cola |
| `POST /v1/cola` | `{"consulta": "…"}` o `{"id": "<videoId>"}`, y `"donde": "siguiente\|final"` (por defecto `final`) | `{"ok":true,"cancion":{…},"donde":"final"}` |
| `DELETE /v1/cola/{indice}` | | Estado |
| `POST /v1/cola/vaciar` | | Estado |
| `GET /v1/listas` | | `{"ok":true,"listas":[{"id","nombre","canciones","fijada"}]}` |
| `POST /v1/listas/{id}/reproducir` | Opcional `{"aleatorio": true}` | Qué empezó a sonar |
| `POST /v1/listas/{id}/anadir` | Opcional `{"id": "<videoId>"}`; sin `id`, la canción actual | `{"ok":true,"cancion":{…},"lista":{"id","nombre"},"duplicada":false}` |
| `POST /v1/mostrar` | | Estado; muestra la ventana de Vegas |

- `POST /v1/listas/{id}/añadir` también vale. Es la misma ruta, para quien no quiera codificar la ñ.
- **«Me gusta»:** no existe en Vegas para PC, así que no hay endpoint.

**Estado:**

```json
{
  "ok": true,
  "reproduciendo": true,
  "cancion": {"id": "fJ9rUzIMcZQ", "titulo": "Bohemian Rhapsody", "artista": "Queen", "album": "A Night at the Opera", "duracion": 354.0},
  "posicion": 12.5,
  "duracion": 354.0,
  "volumen": 0.8,
  "aleatorio": false,
  "repeticion": "no",
  "cola": {"total": 20, "posicion": 3},
  "lista": {"id": "…", "nombre": "Rock"}
}
```

- **Sin nada en la cola:** `cancion` es `null`, `posicion` vale 0 y `cola.posicion` vale -1.
- **`lista`:** la lista propia desde la que se empezó la cola. Es `null` si la cola vino de una búsqueda, un álbum o un artista.
- **Coste:** sale de memoria, sin red ni base de datos. Se puede pedir cada pocos segundos.

**Qué empezó a sonar** (`/v1/reproducir`, `/v1/listas/{id}/reproducir`):

```json
{"ok": true, "tipo": "artista", "titulo": "Queen", "artista": null, "id": "UC…", "canciones": 20}
```

**Cómo elige `/v1/reproducir`:**
- **`cancion`:** la primera canción encontrada. Al acabarse la cola, sigue con canciones parecidas, como en la app.
- **`artista`:** las canciones más escuchadas del primer artista encontrado.
- **`album`:** el primer álbum encontrado, entero.
- **`lista`:** entre las listas propias, la de nombre más parecido. Ignora mayúsculas y tildes, y acepta nombres aproximados.
- **`auto`:**
  1. Si hay una lista propia con ese nombre (casi exacto), esa.
  2. Si no, el primer resultado de YouTube Music, sea canción, artista o álbum.

**La cola** (`GET /v1/cola`):

```json
{"ok": true, "posicion": 0, "canciones": [{"indice": 0, "id": "…", "titulo": "…", "artista": "…", "album": null, "duracion": 215.0}]}
```

**Detalles de la cola:**
- **`/v1/cola/vaciar`:** deja solo la canción que suena.
- **`DELETE` de la canción que suena:** falla con `indice_actual`.

### Códigos de error

| HTTP | `error` | Cuándo |
|---|---|---|
| 401 | `token` | Falta el token o no es válido |
| 403 | `host` | Cabecera `Host` distinta de `127.0.0.1:47322` / `localhost:47322` |
| 403 | `origen` | Petición con `Origin` (desde una web) o desde fuera del equipo |
| 413 | `cuerpo_grande` | Cuerpo de más de 16 KB |
| 415 | `tipo_contenido` | Cuerpo sin `Content-Type: application/json` |
| 429 | `tasa` | Demasiadas peticiones |
| 400 | `peticion` | JSON mal formado, falta un campo o tiene otro tipo |
| 400 | `tipo` | `tipo` no es `cancion`, `artista`, `album`, `lista` ni `auto` |
| 400 | `valor` | Volumen fuera de 0–1, segundos negativos o `modo` no válido |
| 400 | `donde` | `donde` no es `siguiente` ni `final` |
| 404 | `sin_resultados` | La búsqueda o la id no dan nada |
| 404 | `lista_no_encontrada` | No existe una lista con esa id |
| 404 | `indice` | No hay canción en ese índice de la cola |
| 404 | `ruta` | La ruta o el método no existen |
| 409 | `nada_sonando` | Orden que necesita una canción y la cola está vacía |
| 409 | `indice_actual` | Quitar de la cola la canción que suena |
| 409 | `lista_vacia` | Reproducir una lista sin canciones |
| 502 | `red` | No se pudo consultar YouTube Music |
| 500 | `interno` | Fallo inesperado de Vegas |

### Probar a mano

En PowerShell (`curl.exe`, no el alias `curl`):

```powershell
$t = Get-Content "$env:APPDATA\Vegas\control.token"
curl.exe http://127.0.0.1:47322/v1/salud
curl.exe -H "Authorization: Bearer $t" http://127.0.0.1:47322/v1/estado
curl.exe -H "Authorization: Bearer $t" -H "Content-Type: application/json" -d '{\"consulta\":\"Queen\",\"tipo\":\"artista\"}' http://127.0.0.1:47322/v1/reproducir
curl.exe -X POST -H "Authorization: Bearer $t" http://127.0.0.1:47322/v1/pausa
curl.exe -H "Authorization: Bearer $t" -H "Content-Type: application/json" -d '{\"valor\":0.3}' http://127.0.0.1:47322/v1/volumen
curl.exe -H "Authorization: Bearer $t" http://127.0.0.1:47322/v1/listas
```

## Móvil (Android)

### Paquetes

- **Vegas:** `dev.alextorres.vegas`.
- **Vegas de depuración:** `dev.alextorres.vegas.debug`.

### El servicio de medios

- **Servicio:** `com.metrolist.music.playback.MusicService` (el nombre de clase se mantiene del fork).
- **Exportado** con las acciones `android.media.browse.MediaBrowserService`, `androidx.media3.session.MediaLibraryService` y `androidx.media3.session.MediaSessionService`.
- **Media3 1.10:** atiende también a clientes `android.media.browse.MediaBrowser` sin librerías.

### Lo estándar, abierto a cualquier app

Igual que en Metrolist, porque lo usan Android Auto, Bluetooth y el Asistente:

- **Navegar:**
  - La raíz tiene secciones configurables: Me gusta, Canciones, Artistas, Álbumes, Listas y Mixes.
  - Hay búsqueda (`search`).
- **Reproducir:**
  - `playFromSearch(consulta, extras)`. Si la consulta coincide con el nombre de una lista propia, reproduce esa lista; si no, la mejor canción.
  - `playFromMediaId(id)` con las ids del árbol de navegación.
- **Controles:** `play`, `pause`, `skipToNext`, `skipToPrevious`, `seekTo`, `skipToQueueItem` y `stop`.
- **Cola:** `getQueue()`.
- **Volumen del sistema:** `setVolumeTo` / `adjustVolume`.
- **Acciones personalizadas que ya existían:**
  - `TOGGLE_LIKE`: «Me gusta», que en el móvil sí existe.
  - `TOGGLE_START_RADIO`.
  - `TOGGLE_SHUFFLE`.
  - `TOGGLE_REPEAT_MODE`.
  - `ADD_TO_TARGET_PLAYLIST`.
- **MediaSession completa:** título, artista, álbum, carátula, duración y estado. JARVIS la ve con `MediaSessionManager.getActiveSessions`.

### Órdenes propias de Vegas, solo para Vegas y JARVIS

Se mandan con `MediaController.TransportControls.sendCustomAction(acción, extras)`:

| Acción | Extras |
|---|---|
| `dev.alextorres.vegas.control.ALEATORIO` | `activo`: Boolean |
| `dev.alextorres.vegas.control.REPETIR` | `modo`: String `no\|lista\|una` |
| `dev.alextorres.vegas.control.VOLUMEN` | `valor`: Float o Double, de 0 a 1 (volumen de Vegas, no el del sistema) |
| `dev.alextorres.vegas.control.COLA_ANADIR` | `id`: String (videoId) o `consulta`: String; `donde`: `siguiente\|final` (por defecto `final`) |
| `dev.alextorres.vegas.control.COLA_QUITAR` | `indice`: Int (desde 0; no puede ser la que suena) |
| `dev.alextorres.vegas.control.COLA_VACIAR` | Ninguno; deja solo la que suena |

**Quién puede:**
- Vegas y la app cuyo uid pertenezca a `com.jarvis.mobile` y esté firmada con el certificado de SHA-256 `1afdae2a0388820963c73c795d8ba738cb6e079b6ef60dbe2cddc075e1fa127e` (la clave estable de CI, CN=JARVIS).
- **Cómo se comprueba:** por el uid que da el sistema, no por el nombre que dice el llamante.
- **Cualquier otra app:** no recibe estas acciones al conectarse y, si las manda igualmente, se ignoran.

**Avisos:**
- Un APK de JARVIS compilado en local, sin la clave de CI, no pasa la comprobación.
- Si JARVIS cambia de clave, hay que actualizar `TrustedCallers.JARVIS_CERT_SHA256` en Vegas.
- Desde un cliente `android.media` sin librerías, la comprobación necesita Android 9 o superior. En versiones anteriores el sistema no da el uid del llamante y se rechaza.
- `sendCustomAction` no devuelve resultado: JARVIS comprueba el efecto leyendo la sesión (cola, modo aleatorio y repetición del `PlaybackState`).

### Diferencias con Metrolist

- **Se quitó:** la acción personalizada `TOGGLE_LIBRARY` (añadir a la biblioteca de YouTube), porque Vegas no usa cuenta de YouTube.
- **Se quitaron también** las partes sin uso en Vegas: podcasts, Cast y actualizador.
- **Se conserva:** todo lo demás de la superficie de medios.
