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
- **Sincronización con el móvil:** fases 2 y 3, hechas (ver abajo).
- **Descargas en el PC:** fase 4, pendiente.

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
