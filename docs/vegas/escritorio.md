# Vegas para Windows

App de escritorio de Vegas (módulo `:desktop`): Kotlin/JVM + Compose Multiplatform. El diseño completo está en `docs/superpowers/specs/2026-10-23-vegas-escritorio-design.md`.

## Compilar y probar

```bash
./gradlew :desktop:test        # tests
./gradlew :desktop:run         # abrir la app sin instalar
./gradlew :desktop:packageMsi  # instalador
```

- **Instalador:** se genera en `desktop/build/compose/binaries/main/msi/Vegas-<versión>.msi`.
- **Qué incluye:** su propio Java y FFmpeg (unos 150 MB), así que no hay que instalar nada más.
- **Dónde se instala:** solo para tu usuario, sin permisos de administrador, en `%LOCALAPPDATA%\Vegas`. Crea un acceso en el menú Inicio y otro en el escritorio.
- **Versión:** `packageVersion` en `desktop/build.gradle.kts`. Es independiente de la versión de Android. Para que Windows actualice una instalación existente hay que subirla.

## Dónde están tus datos

`%APPDATA%\Vegas\` (normalmente `C:\Users\<usuario>\AppData\Roaming\Vegas\`):

| Archivo | Qué es |
|---|---|
| `vegas.db` | Tus listas (base SQLite) |
| `settings.json` | Ajustes y tamaño de la ventana |
| `queue.json` | La cola, para seguir donde lo dejaste |
| `downloads-tmp\` | Descargas a medias (se reanudan solas) |
| `crashes\` | Los 5 últimos informes de error, si los hay |
| `control.token` | La clave con la que JARVIS controla Vegas (solo la lee tu usuario) |
| `control.log` | Intentos rechazados contra la API de control |
| `vegas.lock` | Evita que se abran dos Vegas a la vez |

Desinstalar la app no borra estos datos.

## Cómo funciona por dentro

- **Catálogo:** las fuentes de `innertube/` se compilan dentro de `:desktop`. Un sustituto de `timber.log.Timber` (`desktop/src/main/kotlin/timber/log/Timber.kt`) evita depender de Android. El módulo `innertube` no se toca.
- **Audio:**
  - InnerTubeX (variante JVM) resuelve el stream sin PO-token (`playback/StreamResolver.kt`).
  - FFmpeg (JavaCV) lo decodifica en `playback/AudioEngine.kt`.
  - Java Sound lo reproduce.
- **Teclas multimedia:** usa `RegisterHotKey` solo para esas cuatro teclas (`system/MediaKeys.kt`). Si otra app ya tiene una tecla, esa tecla sigue yendo a esa app.
- **Panel de medios de Windows (SMTC):** pendiente.
- **Control desde JARVIS:** API local en `127.0.0.1:47322` con token, arranque con `--silencioso` (ventana oculta, icono en la bandeja) e instancia única. El contrato está en `docs/vegas/control-jarvis.md`.
- **Sincronización con el móvil:** fases 2 y 3, hechas (ver abajo).
- **Descargas en el PC:** fase 4, hecha (ver abajo).

## Sincronización con el móvil

**Vincular, una sola vez:**
1. En el PC: Ajustes → «Vegas en el móvil» → «Vincular un móvil». Muestra un código de 6 dígitos válido 2 minutos; al tercer intento fallido deja de valer.
2. En el móvil: Ajustes → «Vegas para PC», elegir el PC y escribir el código.
3. Si Windows pregunta por el Firewall, permitir Vegas en **redes privadas**.

**Qué se sincroniza:**
- **Listas:** las propias, con sus canciones en orden, nombre, si están fijadas y orden personalizado.
- **Ajustes:** calidad de audio, ocultar explícito, ocultar canciones de vídeo, saltar silencios y negro puro.
- **No se sincronizan:** descargas, «Me gusta» ni las listas guardadas de YouTube.

**Cuándo se sincroniza:**
- Solo en el wifi de casa.
- Cada minuto con la app abierta en el móvil.
- 5 s después de editar una lista.
- Cada 15 min en segundo plano.

**Si se cambia lo mismo en los dos aparatos:**
- Se juntan los cambios.
- Si los dos tocan la misma canción o la misma lista, gana el cambio más reciente.

**Seguridad:**
- Conexión HTTPS al puerto 47321, con el certificado del PC fijado en el móvil al vincular.
- El servidor solo acepta direcciones de red privada.
- El móvil guarda su token cifrado con Android Keystore; el PC solo guarda su hash (`paired.json`).
- El certificado del PC está en `sync.p12` y `sync.key`, dentro de `%APPDATA%\Vegas`.

## Descargas y modo sin conexión

El diseño está en `docs/superpowers/specs/2026-10-25-vegas-escritorio-descargas-design.md`. Funciona igual que en el móvil.

**Qué se descarga:**
- **Listas:** en la página de una lista, «Descargar». Las canciones que se añadan después, también las que lleguen del móvil por sincronización, se descargan solas.
- **Canciones sueltas:** menú de la canción → «Descargar».
- Lo que descargas en el PC no se descarga en el móvil, ni al revés: cada aparato decide.

**Dónde se guarda:**
- En `Música\Vegas`, con el mismo nombre que en el móvil (`Artista - Título [videoId].m4a`) y con título, artista, álbum y carátula dentro del archivo.
- Se puede cambiar en Ajustes → «Descargas». Lo ya descargado se queda donde está.
- Al arrancar, Vegas reconoce los archivos de la carpeta que tengan `[videoId]` en el nombre. Si copias ahí la carpeta `Música/Vegas` del móvil, esas canciones ya cuentan como descargadas. También se puede buscar a mano desde Ajustes.

**Quitar descargas:**
- «Quitar descarga» de una lista pregunta si quieres borrar los archivos (por defecto no). Si dices que sí, solo se borran los que no estén en otra lista descargada.
- «Borrar descarga» de una canción la borra. Si sigue en una lista descargada, se vuelve a descargar.
- Quitar una canción de una lista no borra su archivo.
- Los archivos borrados van a la Papelera.

**Sin conexión:**
- Una canción descargada siempre suena desde su archivo, sin red.
- Si no hay red, o una canción no descargada tarda más de 4 s en empezar, se salta a la siguiente descargada de la cola.
- Si no queda ninguna descargada en la cola, se pausa con un aviso.

**Descargas en marcha:**
- Se descargan 3 a la vez, o 1 mientras suena algo en streaming.
- Si una falla, se reintenta a los 1, 5 y 15 min. Después aparece en Ajustes con «Reintentar».
- Si se va la red, se reintenta sola cada minuto.
