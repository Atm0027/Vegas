# Trabajar en Vegas como agente de IA

Vegas es un reproductor de música personal (Android + Windows) en Kotlin y Material 3, derivado de Metrolist (GPL-3.0).

## Reglas

1. Responder en español. Commits con el formato `type(scope): descripción`.
2. No renombrar la app, no reintroducir el icono ni la marca de Metrolist y no subir la versión salvo que se pida. El namespace `com.metrolist` del código se mantiene.
3. Funcionalidades nuevas: brainstorming → diseño → plan → implementación. Los diseños, los planes y las reglas para traer cambios de Metrolist son internos y se guardan fuera del repo (`docs/superpowers/` está en `.gitignore`).
4. No editar el esquema de la base de datos salvo que sea imprescindible; si cambia, añadir migración.
5. No imprimir ni subir secretos, cookies ni datos personales (correo, rutas locales, números de serie).
6. No reescribir el historial ni forzar push sin permiso explícito.

## Compilar y probar

Con JDK 21:

```bash
./gradlew :synccore:test :desktop:test :app:testPersonalDebugUnitTest :app:assemblePersonalDebug
```

El APK de depuración queda en `app/build/outputs/apk/personal/debug/`. La app de Windows se abre con `./gradlew :desktop:run`.
