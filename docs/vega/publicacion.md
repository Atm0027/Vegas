# Vega — licencias y publicación

Revisado el 2026-10-16. No es asesoramiento legal; es la lectura de las licencias y políticas aplicables.

## Licencias

| Componente | Licencia | Qué implica |
|---|---|---|
| Metrolist (base de Vega) | GPL-3.0 | Se puede modificar y redistribuir, también públicamente |
| InnerTubeX (extracción de YouTube) | GPL-3.0 | Igual; compatible |
| AndroidX, Media3, Room, Hilt, Coil, Ktor, Timber, uCrop, Reorderable… | Apache-2.0 / MIT | Compatibles con GPL-3.0 |
| Google Cast / Play Services (flavor `gms`) | Propietaria | Solo en el flavor con Cast; el flavor `personal` no lo incluye |

Lo hecho hasta ahora (fork, quitar funciones, cambiar nombre e icono, mejoras propias) está permitido por la GPL-3.0.

## Obligaciones si se publica

1. **Licencia:** Vega entera debe distribuirse bajo GPL-3.0 (sin restricciones añadidas) y con el `LICENSE`.
2. **Código fuente:** publicar el código correspondiente a cada APK (repo público o enlace al código exacto de esa versión).
3. **Aviso de cambios (GPL §5a):** indicar que es una versión modificada de Metrolist y cuándo. El historial de git y un apartado en el README sirven.
4. **Créditos:** conservar los avisos de copyright y la autoría de Metrolist (cabeceras de los archivos y la pantalla «Acerca de»).

## Marca y nombre

- Metrolist pertenece a «Metrolist Group LLC». Vega ya usa nombre e icono propios: bien.
- Antes de publicar hay que quitar los textos que presentan la app como «Metrolist» (unas 12 cadenas por idioma, p. ej. «Toca para abrir Metrolist», «Reproducir en Metrolist», informes de fallo, carpetas `Pictures/Metrolist` y `MetrolistExports`, cabeceras `X-Title`, resumen anual «Metrolist 2025»), salvo los créditos.
- El enlace de informes de fallo y de versiones apunta a GitHub de Metrolist: cambiarlo al repo de Vega, para no enviarles fallos que no son suyos.
- «Basado en Metrolist» es correcto como atribución; no sugerir respaldo de Metrolist, YouTube ni Google (mantener el descargo de responsabilidad del README).

## Dónde se puede publicar

- **Google Play: no.** Las políticas de Play prohíben apps que acceden a YouTube sin la API oficial o que descargan su contenido; sería rechazada o retirada. Ningún cliente de este tipo (NewPipe, Metrolist, ViMusic…) está en Play.
- **GitHub Releases: sí**, como hacen Metrolist y similares.
- **F-Droid / IzzyOnDroid: posible** con el flavor sin Google (`personal`/`foss`); marcarían el antirrasgo «NonFreeNet» (depende de un servicio no libre).

## Riesgo con YouTube

Usar YouTube sin la API oficial va contra sus Condiciones de servicio. Es un riesgo del proyecto y de los usuarios (bloqueos, avisos), no un problema de licencia. Que Vega no tenga inicio de sesión reduce el riesgo para las cuentas de los usuarios. Proyectos como NewPipe y Metrolist llevan años publicados así.

## Lista antes de publicar

- [ ] Repo público (o código fuente enlazado en cada release).
- [ ] README de Vega: qué es, «basado en Metrolist (GPL-3.0)», cambios, descargo de responsabilidad.
- [ ] Quitar la marca Metrolist de los textos visibles (dejar créditos).
- [ ] Informes de fallo y enlaces de versiones → repo de Vega.
- [ ] Firmar los APK de release con una clave propia (no la de depuración).
- [ ] Publicar solo flavors sin servicios de Google, o declarar Cast como propietario.
