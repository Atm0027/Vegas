# Vegas — Sincronización con Metrolist

Vegas no fusiona Metrolist tal cual. Cada cambio nuevo de `upstream/main` se **revisa, se adapta
a Vegas y se implementa solo si conviene**. Este documento son las reglas; el estado vive en
`docs/vegas/upstream-state.txt` (último commit de upstream ya revisado).

## Qué es Vegas (lo que no se puede romper)

- App personal, solo audio, sin cuenta ni sincronización con YouTube, biblioteca local.
- `applicationId = dev.alextorres.vegas`, flavor `personal` (sin Cast, sin actualizador).
- **Eliminado a propósito, no reintroducir:** Escuchar juntos (Listen Together) y protobuf, Discord, login/cuenta de YouTube y sincronización de biblioteca (`SyncUtils`). Al arrancar se borran datos de cuenta y Discord (`LegacyAccountCleanup`); `visitorData` anónimo se conserva. Tampoco: subir canciones a YouTube, historial remoto, podcasts de cuenta. Tampoco (quitado por decisión de Alex): podcasts en toda la app (biblioteca, inicio, búsqueda, páginas de artista; se filtran con `filterPodcasts()`), las listas automáticas «En caché» y «Top 50» de la biblioteca (el widget sigue usando `top_playlist`), y «Añadir/Quitar de la biblioteca» para canciones.
- **Sin rastro del icono de Metrolist** (decisión de Alex). El único icono es la V de Vegas (`ic_vegas_*`, `small_icon` y `assets/ic_launcher-playstore.png`). Se rechazan los cambios de upstream que reintroduzcan `ic_launcher_foreground`, `ic_launcher_monochrome`, `ic_launcher_static*` o los `mipmap-*/ic_launcher*.webp`/`.png`.
- **Sincronización con Vegas para PC** (propia de Vegas):
  - El módulo `:synccore` y `app/.../sync/`, que contiene las tablas `sync_*` de la base de datos v41 (`AutoMigration` 40→41).
  - Si upstream sube la versión de la base de datos, la de Vegas va por delante: hay que crear una migración nueva encima, no sustituirla.
- **Quitado del repo, no reintroducir desde upstream:** `.github/` (workflows de Blacksmith, plantillas, FUNDING), `fastlane/`, `changelog.md`, `development_guide.md`, `crowdin.yml`, `renovate.json` y las imágenes de donaciones y badges de `assets/`. Los planes (`docs/superpowers/plans/`) se quedan en local, no se suben.
- **No ampliar:** vídeo (Metrolist ya no tiene reproductor de vídeo), Google Cast.
- **Se mantiene:** Android Auto.
- Lógica propia de Vegas que un cambio de upstream no debe pisar:
  `playback/ShuffleOrders.kt`, `playback/StallRecovery.kt`, `playback/StreamResolveTimeout.kt`,
  `playback/FastFailLoadErrorPolicy.kt`, el uso de `extendShuffleOrder`/`userAddedMediaIds`
  en `MusicService.kt`, `buildSrc/` (versionado) y la identidad en `app/build.gradle.kts`.

## Mejoras propias de Vegas (comparar antes de adoptar)

Si un commit de upstream toca algo de esta tabla, **no se adopta por defecto**. Se comparan las
dos soluciones y gana la mejor para el usuario:

- **La de Vegas es mejor o igual** → descartar el commit (motivo: "Vegas ya lo resuelve mejor").
- **Upstream aporta algo que Vegas no tiene** (caso que no cubríamos, arreglo de un fallo nuestro,
  mejor rendimiento) → integrar **solo esa parte** encima de la solución de Vegas, sin sustituirla.
- **Upstream es claramente mejor en conjunto** → adoptarlo, pero conservando los tests de Vegas
  (deben seguir en verde) y anotarlo en el registro para que Alex lo sepa.

En la duda, quedarse con lo de Vegas y anotar el commit como "pendiente de decisión humana".

| Área | Mejora de Vegas | Dónde | Garantía |
|---|---|---|---|
| Aleatorio / anterior | El historial de "anterior" sobrevive a cargar más canciones, añadir a la cola y activar el aleatorio; "primero la playlist" respeta lo añadido por el usuario | `ShuffleOrders.kt`, `extendShuffleOrder`/`userAddedMediaIds` en `MusicService.kt` | `ShuffleOrdersTest` |
| Canciones atascadas | Recuperación a los 6–8 s, también a mitad de canción, sin actuar sin red | `StallRecovery.kt`, `updateInitialBufferRecovery` | `StallRecoveryTest` |
| Resolución del stream | Tiempo máximo de 20 s | `StreamResolveTimeout.kt` | `StreamResolveTimeoutTest` |
| URLs caducadas | 403/410 no se reintentan contra la misma URL | `FastFailLoadErrorPolicy.kt` | `FastFailLoadErrorPolicyTest` |
| Cortes cada ~9 s | La reproducción pide el rango hasta el final del archivo; googlevideo limita las peticiones abiertas a velocidad de reproducción (Metrolist solo lo arregló en descargas, #4369) | `withResolvedStream(..., contentLength)` en `StreamUrlCache.kt` | `ResolvedStreamRangeTest` |
| Menús | Acción "Reproducir" visible en menús de álbum/playlist | `ui/menu/*Menu.kt` | — |
| Versionado | Estilo JARVIS, independiente de Metrolist | `buildSrc/` | `VersionSemanticaTest` |
| Editor de listas | Reordenar, quitar, renombrar y portada se aplican en una sola transacción al pulsar "Hecho", con Deshacer; sin escrituras por cada arrastre | `PlaylistEditSession.kt`, `applyPlaylistEdit`/`restorePlaylistSnapshot` en `DatabaseDao.kt`, `LocalPlaylistScreen.kt` | `PlaylistEditSessionTest`, `ApplyPlaylistEditTest` |
| Añadir a listas | Varias listas a la vez, nuevas arriba, sin duplicados exactos y preguntando por los probables (mismo título/artista, ±3 s) | `DuplicateMatcher.kt`, `PlaylistAddPlanner.kt`, `AddToPlaylistDialog.kt` | `DuplicateMatcherTest`, `PlaylistAddPlannerTest`, `AddSongsToPlaylistTest` |
| Organizar biblioteca | Orden personalizado (arrastrar) y listas fijadas arriba con cualquier orden; BD 39 (`customOrder`, `pinned`) | `DatabaseDao.playlists`, `LibraryPlaylistsScreen.kt`, `PlaylistMenu.kt` | `PlaylistOrganizeTest` |
| Modo sin conexión | Descargas como archivos reales en `Música/Vegas` (MediaStore), cola con WorkManager (máx. 3 a la vez, 1 mientras suena una canción no descargada), playlists marcadas que descargan solas lo nuevo, el archivo local se reproduce primero, sin red salta a la siguiente canción reproducible y pausa al final, detección de red fiable con VPN, reanudación segura de descargas | `offline/`, `DownloadUtil.kt`, `LocalFirstDataSource.kt`, resolutor y `onPlayerError` de `MusicService.kt`, `NetworkConnectivityObserver.kt` | `OfflineDaoTest`, `SongDownloaderTest`, `OfflineSyncTest`, `OfflineStoreTest`, `SourceLockTest`, `DownloadSlotsTest`, `NetworkFailureTest`, `Mp4FragmentsTest`, `PendingDiffTest` |

Cada vez que Vegas mejore algo nuevo, se añade una fila aquí.

## Clasificación de cada commit de upstream

| Tipo | Ejemplos | Decisión |
|---|---|---|
| **Adoptar** | arreglos de reproducción, InnerTube/InnerTubeX, cifrado/PoToken, cierres, batería, descargas, letras, base de datos, Android Auto, rendimiento | Adaptar e implementar |
| **Valorar** | funciones nuevas de UI, ajustes nuevos, refactors grandes | Implementar solo si encaja con Vegas y no choca con lo anterior |
| **Descartar** | Listen Together, Discord, login/cuenta/sync YouTube (incl. cambios en `SyncUtils`, `LoginScreen`, `AccountSettings`), vídeo, Cast, updater, Firebase/telemetría, subidas de versión de Metrolist, CI/workflows, traducciones de textos que Vegas ya no tiene, cambios en «Acerca de», «Novedades» (ChangelogScreen) y textos de marca Metrolist | No implementar; anotar el motivo |

Las traducciones: solo se adoptan cadenas que Vegas usa.

Descargas: Vegas sustituyó el sistema de descargas de Metrolist (`ExoDownloadService`, `DownloadManager` y la caché de descargas) por el suyo propio. Los commits de upstream sobre ese sistema se descartan ("Vegas ya lo resuelve mejor"); si arreglan algo útil (p. ej. cómo pedir el stream a googlevideo), se aplica solo esa idea dentro de `offline/SongDownloader.kt`.

## Cómo se implementa

1. Trabajar en una rama `sync/metrolist-AAAA-MM-DD` creada desde `main`.
2. Por cada commit "Adoptar"/"Valorar" aceptado: `git cherry-pick -x <sha>`; si choca, resolver
   a mano adaptando el cambio a Vegas (no deshacer lo de Vegas). Si no tiene sentido literal,
   reescribirlo a mano y citar el sha original en el mensaje.
3. Mensajes de commit en Conventional Commits (alimentan el versionado de Vegas).
4. Verificar: `./gradlew :app:assemblePersonalDebug :app:testPersonalDebugUnitTest` con
   `JAVA_HOME="C:/Program Files/Microsoft/jdk-21.0.12.101-hotspot"`. Si algo falla y no se
   arregla, revertir ese commit concreto y anotarlo.
5. Con todo en verde: fusionar la rama en `main` (`git merge --no-ff`) y `git push origin main`.
6. Actualizar `docs/vegas/upstream-state.txt` y añadir una entrada al registro de abajo.

Nunca: reescribir historia, `push --force`, borrar ramas ajenas, tocar el remoto `upstream`.

## Registro

| Fecha | Upstream revisado hasta | Adoptados | Descartados | Notas |
|---|---|---|---|---|
| 2026-10-16 | 76fa5a78 | — | — | Punto de partida del fork |
| 2026-10-26 | c4c61fb3 | InnerTubeX 0.10.0 → 0.11.0 (QuackJS, quita el solver EJS empaquetado) (d4f4a346, 6d085042); «Quitar de la cola» en el menú de la cola (fae57e7f, adaptado); tocar la miniatura en letras a pantalla completa para reproducir/pausar (2872811c, sin Listen Together ni Cast); traducciones de Weblate solo de cadenas que Vegas usa | SECURITY.md (b1dd1d06, docs de Metrolist); historial de YouTube Music con cuenta (40d9c70d, Vegas no tiene cuenta); nueva traducción parcial al birmano (values-my); cadenas de KMP/actualizador | 4 traducciones que Vegas tiene distintas se conservan (ca `tap_to_open`, pl `last_fm_send_likes_description`, es-rUS/zh-rCN `kmp_upgrade_warning`) |
