# Vega — Sincronización con Metrolist

Vega no fusiona Metrolist tal cual. Cada cambio nuevo de `upstream/main` se **revisa, se adapta
a Vega y se implementa solo si conviene**. Este documento son las reglas; el estado vive en
`docs/vega/upstream-state.txt` (último commit de upstream ya revisado).

## Qué es Vega (lo que no se puede romper)

- App personal, solo audio, sin cuenta ni sincronización con YouTube, biblioteca local.
- `applicationId = dev.alextorres.vega`, flavor `personal` (sin Cast, sin actualizador).
- **Eliminado a propósito, no reintroducir:** Escuchar juntos (Listen Together) y protobuf, Discord, login/cuenta de YouTube y sincronización de biblioteca (`SyncUtils`). Al arrancar se borran datos de cuenta y Discord (`LegacyAccountCleanup`); `visitorData` anónimo se conserva. Tampoco: subir canciones a YouTube, historial remoto, podcasts de cuenta.
- **No ampliar:** vídeo (Metrolist ya no tiene reproductor de vídeo), Google Cast.
- **Se mantiene:** Android Auto.
- Lógica propia de Vega que un cambio de upstream no debe pisar:
  `playback/ShuffleOrders.kt`, `playback/StallRecovery.kt`, `playback/StreamResolveTimeout.kt`,
  `playback/FastFailLoadErrorPolicy.kt`, el uso de `extendShuffleOrder`/`userAddedMediaIds`
  en `MusicService.kt`, `buildSrc/` (versionado) y la identidad en `app/build.gradle.kts`.

## Mejoras propias de Vega (comparar antes de adoptar)

Si un commit de upstream toca algo de esta tabla, **no se adopta por defecto**. Se comparan las
dos soluciones y gana la mejor para el usuario:

- **La de Vega es mejor o igual** → descartar el commit (motivo: "Vega ya lo resuelve mejor").
- **Upstream aporta algo que Vega no tiene** (caso que no cubríamos, arreglo de un fallo nuestro,
  mejor rendimiento) → integrar **solo esa parte** encima de la solución de Vega, sin sustituirla.
- **Upstream es claramente mejor en conjunto** → adoptarlo, pero conservando los tests de Vega
  (deben seguir en verde) y anotarlo en el registro para que Alex lo sepa.

En la duda, quedarse con lo de Vega y anotar el commit como "pendiente de decisión humana".

| Área | Mejora de Vega | Dónde | Garantía |
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

Cada vez que Vega mejore algo nuevo, se añade una fila aquí.

## Clasificación de cada commit de upstream

| Tipo | Ejemplos | Decisión |
|---|---|---|
| **Adoptar** | arreglos de reproducción, InnerTube/InnerTubeX, cifrado/PoToken, cierres, batería, descargas, letras, base de datos, Android Auto, rendimiento | Adaptar e implementar |
| **Valorar** | funciones nuevas de UI, ajustes nuevos, refactors grandes | Implementar solo si encaja con Vega y no choca con lo anterior |
| **Descartar** | Listen Together, Discord, login/cuenta/sync YouTube (incl. cambios en `SyncUtils`, `LoginScreen`, `AccountSettings`), vídeo, Cast, updater, Firebase/telemetría, subidas de versión de Metrolist, CI/workflows, traducciones de textos que Vega ya no tiene | No implementar; anotar el motivo |

Las traducciones: solo se adoptan cadenas que Vega usa.

## Cómo se implementa

1. Trabajar en una rama `sync/metrolist-AAAA-MM-DD` creada desde `main`.
2. Por cada commit "Adoptar"/"Valorar" aceptado: `git cherry-pick -x <sha>`; si choca, resolver
   a mano adaptando el cambio a Vega (no deshacer lo de Vega). Si no tiene sentido literal,
   reescribirlo a mano y citar el sha original en el mensaje.
3. Mensajes de commit en Conventional Commits (alimentan el versionado de Vega).
4. Verificar: `./gradlew :app:assemblePersonalDebug :app:testPersonalDebugUnitTest` con
   `JAVA_HOME="C:/Program Files/Microsoft/jdk-21.0.12.101-hotspot"`. Si algo falla y no se
   arregla, revertir ese commit concreto y anotarlo.
5. Con todo en verde: fusionar la rama en `main` (`git merge --no-ff`) y `git push origin main`.
6. Actualizar `docs/vega/upstream-state.txt` y añadir una entrada al registro de abajo.

Nunca: reescribir historia, `push --force`, borrar ramas ajenas, tocar el remoto `upstream`.

## Registro

| Fecha | Upstream revisado hasta | Adoptados | Descartados | Notas |
|---|---|---|---|---|
| 2026-10-16 | 76fa5a78 | — | — | Punto de partida del fork |
