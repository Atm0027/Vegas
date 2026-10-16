# Vega — Sincronización con Metrolist

Vega no fusiona Metrolist tal cual. Cada cambio nuevo de `upstream/main` se **revisa, se adapta
a Vega y se implementa solo si conviene**. Este documento son las reglas; el estado vive en
`docs/vega/upstream-state.txt` (último commit de upstream ya revisado).

## Qué es Vega (lo que no se puede romper)

- App personal, solo audio, sin cuenta ni sincronización con YouTube, biblioteca local.
- `applicationId = dev.alextorres.vega`, flavor `personal` (sin Cast, sin actualizador).
- **Eliminado a propósito, no reintroducir:** Escuchar juntos (Listen Together) y protobuf.
- **Ocultar/no ampliar:** login de YouTube, sync de cuenta, vídeo, Discord, Google Cast.
- **Se mantiene:** Android Auto.
- Lógica propia de Vega que un cambio de upstream no debe pisar:
  `playback/ShuffleOrders.kt`, `playback/StallRecovery.kt`, `playback/StreamResolveTimeout.kt`,
  `playback/FastFailLoadErrorPolicy.kt`, el uso de `extendShuffleOrder`/`userAddedMediaIds`
  en `MusicService.kt`, `buildSrc/` (versionado) y la identidad en `app/build.gradle.kts`.

## Clasificación de cada commit de upstream

| Tipo | Ejemplos | Decisión |
|---|---|---|
| **Adoptar** | arreglos de reproducción, InnerTube/InnerTubeX, cifrado/PoToken, cierres, batería, descargas, letras, base de datos, Android Auto, rendimiento | Adaptar e implementar |
| **Valorar** | funciones nuevas de UI, ajustes nuevos, refactors grandes | Implementar solo si encaja con Vega y no choca con lo anterior |
| **Descartar** | Listen Together, login/cuenta/sync YouTube, vídeo, Cast, Discord, updater, Firebase/telemetría, subidas de versión de Metrolist, CI/workflows, traducciones de textos que Vega ya no tiene | No implementar; anotar el motivo |

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
