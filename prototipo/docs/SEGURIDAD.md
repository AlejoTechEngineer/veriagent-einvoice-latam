# Seguridad

Registro de hallazgos de seguridad del prototipo y de su estado. El prototipo usa datos sintéticos y tokens de
demostración: no tiene autenticación real y no es apto para producción.

## Hallazgos

| ID | Severidad | Ubicación | Hallazgo | Estado |
|:---|:---:|:---|:---|:---|
| H-01 | Alta | `agentic-plane/tfg_agents/review_api.py`, `POST /api/proposals/{pid}/decision` | La ruta que aprueba o rechaza propuestas de IA no exige autenticación y toma la identidad del revisor del cuerpo de la petición (`reviewer`). Cualquiera que alcance la API puede aprobar una propuesta que pasó el kit en nombre de otra persona, y la auditoría de `agent_proposal` no es fiable. El bloqueo con 409 de lo que no pasó el kit sí funciona. Origen: revisión automática de seguridad sobre la base heredada. | Pendiente, corrección en la Fase 4 |
| H-02 | Media | `review-console-web/app.js` y `review-console-javafx/.../ReviewApi.java` | Los clientes de revisión envían el revisor como campo del cuerpo: dependen del patrón de H-01. | Pendiente, corrección en las Fases 4 y 5 |
| H-03 | Media | `verification-kit/.../xml/XsdValidator.java` | La validación XSD se ejecuta sobre el texto original antes de que `Dom` rechace el DOCTYPE; el rechazo del DOCTYPE debe ocurrir antes de cualquier análisis. | Pendiente, corrección en la Fase 1 |
| H-04 | Baja | `verification-kit/.../cli/KitHttpServer.java` | El servidor HTTP del kit escucha en todas las interfaces y sin autenticación. `/verify` no cambia estado, pero en el perfil local debe escuchar solo en 127.0.0.1. | Pendiente, corrección en la Fase 1 |

Búsqueda del patrón en el resto de la base (Fase 0): `grep` de rutas `POST/PUT/DELETE/PATCH`, campos
`reviewer`, servidores HTTP, configuración de CORS y analizadores XML en el código Python, Java y JavaScript.
Solo la API de revisión cambia estado en la base; el servidor MCP funciona por stdio; el analizador DOM del kit
ya rechaza DOCTYPE y entidades externas.

## Modelo de seguridad del prototipo (a implementar)

Basado en el apartado 4.4.6 (Seguridad por diseño) del documento y en las decisiones del autor.

- **Denegar por defecto.** Todo endpoint que cambia estado exige token. Roles: `REVIEWER`, `AGENT`,
  `OPERATOR` y `SYSTEM`.
- **Los agentes solo proponen.** El rol `AGENT` nunca puede aprobar, decidir, activar mapeos ni reprocesar la DLQ.
- **La identidad sale del token.** Quién aprobó o quién activó se toma siempre del token, nunca del cuerpo ni de
  cabeceras editables. Un campo `reviewer` en el cuerpo se ignora, o se rechaza si no coincide con el token.
- **Tokens de demostración.** Se generan aleatoriamente al arrancar y se guardan en un archivo local ignorado
  por git. Ningún valor fijo en el repositorio; `.env.example` solo lleva marcadores.
- **Red.** En el perfil local los servicios escuchan solo en 127.0.0.1 y CORS se limita al origen del front.
- **Entradas externas.** Límite de tamaño en la carga de lotes, nombres de archivo saneados (sin recorrido de
  rutas), XML con DTD y entidades externas deshabilitados y validación de esquema antes de procesar.
- **Pruebas automáticas.** Sin token: 401. Token de agente: 403 en decisión, activación y reproceso. La identidad
  registrada en `authorization_log` coincide con la del token. Un intento de suplantación en el cuerpo no tiene
  efecto.

El modelo de roles y la validación de tokens se definen en el módulo compartido de la Fase 1.
