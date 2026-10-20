# Seguridad

Registro de hallazgos de seguridad del prototipo y de su estado. El prototipo usa datos sintéticos y tokens de
demostración: no tiene autenticación real y no es apto para producción.

## Hallazgos

| ID | Severidad | Ubicación | Hallazgo | Estado |
|:---|:---:|:---|:---|:---|
| H-01 | Alta | `agentic-plane/tfg_agents/review_api.py`, `POST /api/proposals/{pid}/decision` | La ruta que aprueba o rechaza propuestas de IA no exige autenticación y toma la identidad del revisor del cuerpo de la petición (`reviewer`). Cualquiera que alcance la API puede aprobar una propuesta que pasó el kit en nombre de otra persona, y la auditoría de `agent_proposal` no es fiable. El bloqueo con 409 de lo que no pasó el kit sí funciona. Origen: revisión automática de seguridad sobre la base heredada. | **Corregido (Fase 4a)**: `tfg_agents/security.py` valida el token contra el mismo archivo de tokens que la plataforma Java; todas las rutas de datos exigen token (401 sin él); solo `REVIEWER` decide (403 para agente y operador); el revisor registrado en `agent_proposal` es la identidad del token y un `reviewer` en el cuerpo que no coincida devuelve 403 sin efecto. Pruebas: `tests/test_review_api.py` (8) y comprobación con uvicorn real. |
| H-02 | Media | `review-console-web/app.js` y `review-console-javafx/.../ReviewApi.java` | Los clientes de revisión envían el revisor como campo del cuerpo: dependen del patrón de H-01. | **Corregido (Fase 4a)**: la consola web y la JavaFX piden el token de acceso (en la pestaña o en la ventana, nunca en la línea de órdenes), lo envían como `Authorization: Bearer`, muestran la identidad que devuelve `GET /api/me` y ya no envían el nombre del revisor. Sin rol de revisor, los botones de decisión quedan deshabilitados. |
| H-03 | Media | `verification-kit/.../xml/XsdValidator.java` | La validación XSD se ejecuta sobre el texto original antes de que `Dom` rechace el DOCTYPE; el rechazo del DOCTYPE debe ocurrir antes de cualquier análisis. | **Corregido (Fase 1)**: `VerificationKit.verifyXml` rechaza el DOCTYPE antes del XSD (regla `R-E0-03`) y el validador XSD usa procesamiento seguro sin acceso externo. Prueba: `rejectsDoctypeBeforeSchemaValidation`. |
| H-04 | Baja | `verification-kit/.../cli/KitHttpServer.java` | El servidor HTTP del kit escucha en todas las interfaces y sin autenticación. `/verify` no cambia estado, pero en el perfil local debe escuchar solo en 127.0.0.1. | **Corregido (Fase 1)**: escucha en 127.0.0.1 por defecto; `--bind 0.0.0.0` solo en la imagen de contenedor. En el plano de integración el kit se expone en `/api/kit/verify`, detrás del token. |
| H-05 | Media | `svc-mapping-registry`, `MappingRegistry.approve` | Un revisor podía proponer una versión de mapeo y aprobarla él mismo: no había separación de funciones entre proponer y aprobar. Origen: revisión automática de seguridad del commit de la Fase 3. | **Corregido (tras la Fase 3)**: quien propuso una versión no puede aprobarla (409). Prueba: `MappingRegistryTest.proposerCannotApprove`. |

## Controles implementados

Política comprobada sobre la aplicación completa (`SecurityPolicyTest`): toda operación que cambia estado declara
`@PreAuthorize` (salvo `KitController.verify` y `RouterController.preview`, de solo lectura) y ninguna ruta GET de la
API responde sin token. Reproceso y descarte de la DLQ: solo OPERATOR (`DlqHandlerTest`: agente y revisor 403,
suplantación 403, identidad del token en `dlq_entry.resolved_by` y en `audit_log`).


| Control | Dónde | Prueba | Estado |
|:---|:---|:---|:---|
| Denegar por defecto (todo exige token salvo salud, OpenAPI y estáticos) | `platform-core` `SecurityConfiguration` | `SecurityTest.noTokenIs401` | Fase 1 |
| Roles y 403 para roles no autorizados | `@PreAuthorize` + `Role` | `SecurityTest.agentCannotDecide` | Fase 1 (núcleo); por endpoint en cada servicio |
| Identidad del token, nunca del cuerpo ni de cabeceras | `CurrentActor`, `IdentityGuard` | `identityComesFromToken`, `impersonationHasNoEffect`, `headersDoNotChangeIdentity` | Fase 1 |
| Tokens aleatorios generados al arrancar, en archivo ignorado | `DemoTokenStore` | `tokensAreRandomAndWrittenToFile` | Fase 1 |
| CORS limitado al origen del front | `SecurityConfiguration` | `corsIsRestricted` | Fase 1 |
| Escucha solo en 127.0.0.1 en el perfil local | `server.address` en `application-local.yml` | `LocalProfileTest.bindsLoopbackOnly` (intenta conectar por cada interfaz no local) | Fase 2 |
| Nombres de archivo saneados y rutas confinadas | `SafeFiles` (ingesta, carpeta SFTP de Beta, publicador, constancias) | `UtilitiesTest.safeFileNames`, `IngestTest.uploadApi`, `RoutingAndClientTest.folderBatch` | Fase 2 |
| Límites de tamaño y de descompresión en la carga de lotes | `VeriagentProperties.Limits`, `spring.servlet.multipart` | `IngestTest.zipLimits` (bomba de descompresión, recorrido de rutas en el ZIP, formato no admitido) | Fase 2 |
| XML sin DTD ni entidades externas | Kit (`Dom`, `XsdValidator`, `VerificationKit`) | `rejectsDoctype`, `rejectsDoctypeBeforeSchemaValidation`, `KitControllerTest.hostileInputs` | Fase 1 |
| Validación de esquema antes de procesar | `ContractValidator` en la ingesta, la fachada y cada evento publicado | `ContractTest`, `IngestTest.invalidItemsGoToDlq`, `InvoiceFacadeTest.validatesBeforeProcessing` | Fase 2 |
| API de revisión de propuestas de IA: token obligatorio, solo REVIEWER decide, identidad del token, suplantación en el cuerpo o en cabeceras sin efecto | `agentic-plane/tfg_agents/security.py`, `review_api.py` | `test_without_token_everything_is_401`, `test_agent_and_operator_cannot_decide`, `test_reviewer_identity_comes_from_the_token`, `test_impersonation_in_body_has_no_effect`, `test_headers_do_not_change_identity` | Fase 4a |
| Agentes con mínimo privilegio: el agente usa su propio token (AGENT); puede proponer, verificar en seco y registrar triajes; aprobar, activar, revertir y reprocesar le devuelven 403; el grafo no tiene nodos de decisión | `platform_client.py`, `agent_orchestrator.py`, `DlqController.triage`, `MappingRegistryController.check` | `DlqHandlerTest.triageIsOnlyARecommendation`, `MappingRegistryTest.dryRunCheck`, `test_cycle_ends_pending_review_and_never_decides`; en vivo: agente aprueba 403, activa 403, reprocesa 403 | Fase 4 |
| La decisión humana se reenvía al registro con el token de la misma persona; un 409 del registro impide la aprobación local | `review_api.py`, `ReviewerForwarder` | `test_human_decision_is_forwarded_with_the_reviewer_token`, `test_registry_conflict_blocks_the_local_approval` | Fase 4 |
| Repositorio de juguete sin merge, confinado a `data/sandbox-repo` (ignorado por git), con nombres saneados y sin recorrido de rutas | `mcp_servers.py` (mcp-repo) | `test_mcp_servers_are_least_privilege` | Fase 4 |
| Registro de mapeos: proponer AGENT, REVIEWER o SYSTEM; aprobar, rechazar, activar y revertir solo REVIEWER; bloqueada por el kit → 409; identidad del token | `svc-mapping-registry` | `MappingRegistryTest.lifecycle` (401, 403 del agente, 403 por suplantación, 409, `approvedBy` y `activatedBy` del token) | Fase 3 |
| Fallos de los simuladores: solo OPERATOR | `provider-simulators` | `ProviderSimulatorTest.adminFaults` (401, 403 de agente y revisor) | Fase 3 |
| Rutas sin token solo para la recepción de los proveedores simulados (`/sim/*/api/v1/**`), declaradas explícitamente en el perfil local y efectivas solo con los simuladores habilitados; la propiedad rechaza cualquier ruta que no empiece por `/sim/` | `VeriagentProperties.Security.simulatorPublicPaths`, `SecurityConfiguration` | `SecurityPolicyTest` (toda otra mutación declara rol), `SimulatorExposureTest` (simuladores desactivados: sin rutas `/sim/` y 401) | Fase 3 |
| Lotes ZIP de los simuladores: límite de tamaño, de descompresión y de documentos; nombres de entrada sin rutas | `SimulatorController.unzip` | `ProviderSimulatorTest.zipTraversal` | Fase 3 |

## Riesgos residuales aceptados

| ID | Riesgo | Decisión del autor | Condiciones que lo acotan | Comprobación |
|:---|:---|:---|:---|:---|
| RR-01 | Las rutas de recepción de los proveedores simulados (`/sim/*/api/v1/**`) aceptan peticiones sin token: cualquiera que alcance el puerto puede enviar documentos a un simulador. Representan sistemas externos, que en la realidad tendrían su propio control de acceso. | **Aceptado (2026-10-19)** | (a) Las rutas `/sim/` solo existen si los simuladores se habilitan por configuración explícita (`veriagent.services.provider-simulators=true`); están desactivados por defecto y solo los activan el perfil local y las pruebas. Con los simuladores desactivados, la lista de rutas públicas de simulador se ignora. (b) En el perfil local la aplicación escucha solo en 127.0.0.1. (c) Las rutas de administración de fallos (`/api/simulators/**`) exigen token y rol OPERATOR, y ninguna ruta de la plataforma puede declararse pública por esta vía. | `SimulatorExposureTest`, `LocalProfileTest.bindsLoopbackOnly`, `ProviderSimulatorTest.adminFaults`, `SecurityPolicyTest` |

Búsqueda del patrón en `agentic-plane` (Fase 4a): rutas que cambian estado, campos `reviewer`, llamadas a
`proposals.decide` y servidores. Resultado: `proposals.decide` solo se invoca desde `review_api.py`; el orquestador
y los agentes guardan propuestas pero nunca deciden; el servidor MCP solo expone herramientas de lectura y
validación por stdio; `kit_client.py` solo llama a la verificación del kit, que no cambia estado. No hay otras
rutas con el patrón de H-01.

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
