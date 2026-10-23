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
| `crashes\` | Los 5 últimos informes de error, si los hay |

Desinstalar la app no borra estos datos.

## Cómo funciona por dentro

- **Catálogo:** las fuentes de `innertube/` se compilan dentro de `:desktop`. Un sustituto de `timber.log.Timber` (`desktop/src/main/kotlin/timber/log/Timber.kt`) evita depender de Android. El módulo `innertube` no se toca.
- **Audio:**
  - InnerTubeX (variante JVM) resuelve el stream sin PO-token (`playback/StreamResolver.kt`).
  - FFmpeg (JavaCV) lo decodifica en `playback/AudioEngine.kt`.
  - Java Sound lo reproduce.
- **Teclas multimedia:** usa `RegisterHotKey` solo para esas cuatro teclas (`system/MediaKeys.kt`). Si otra app ya tiene una tecla, esa tecla sigue yendo a esa app.
- **Panel de medios de Windows (SMTC):** pendiente.
- **Sincronización con el móvil y descargas:** son las fases 2 a 4.
