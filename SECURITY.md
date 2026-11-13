# Seguridad

Vegas es un proyecto personal sin soporte, pero los fallos de seguridad se agradecen.

## Cómo avisar

No abras una issue pública. Usa **[Report a vulnerability](https://github.com/Atm0027/vegas/security/advisories/new)** (avisos privados de GitHub) con:

- qué parte afecta (app de Android, app de Windows, sincronización o API de control),
- cómo reproducirlo,
- qué podría hacer un atacante.

No hay plazos de respuesta garantizados.

## Superficie que más importa

- **Sincronización en la LAN** (puerto 47321): emparejamiento y HTTPS entre móvil y PC.
- **API de control local** (puerto 47322): protegida por un token guardado en `%APPDATA%\Vegas\control.token`.
- **Cuenta de Google opcional**: las cookies de sesión se guardan solo en el dispositivo.

Los fallos que vengan de Metrolist sin cambios conviene avisarlos también en [su repositorio](https://github.com/MetrolistGroup/Metrolist).
