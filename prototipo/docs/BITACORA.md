# Bitácora

Registro de lo hecho en cada fase, con los comandos y el resultado real. Si la sesión se interrumpe, se
retoma desde aquí.

## Fase 0. Entorno y plan (2026-10-19)

### Repositorio
- `git remote -v`: `origin` apunta a `https://github.com/AlejoTechEngineer/veriagent-einvoice-latam.git`.
- Rama `main`; `git pull --rebase origin main`: ya al día.

### Herramientas
| Herramienta | Encontrada | Acción |
|:---|:---|:---|
| Java | JDK 17.0.12 (sistema) y JDK 8 | Se descarga Temurin 21.0.12 portable en `%USERPROFILE%\tools\jdk-21.0.12.1+1` |
| Maven | 3.9.16 | Ninguna |
| Python | 3.14.3 y 3.13 | Se instala `uv` 0.13.1 (usuario) y CPython 3.12.15 portable en `%USERPROFILE%\tools\python` |
| Node | 24.13.1 (npm 11.8.0) | No hace falta: el front no tiene paso de compilación |
| Docker | Cliente 29.8.2 y Compose v5.5.1; `docker info` no responde con datos del servidor | El daemon no está activo: el perfil `full` se validará solo si se puede iniciar |
| JUnit 5 | No instalado | `junit-platform-console-standalone-1.11.4.jar` en `%USERPROFILE%\tools` |
| OpenJFX | No instalado | `javafx-{base,graphics,controls}-21.0.6-win.jar` en `%USERPROFILE%\tools\openjfx-21` |

### Base de la prueba de concepto
- Encontrada en `G:\Mi unidad\Trabajo fin de grado inginformatico\tfg-poc-base.zip` (primera ubicación del orden
  de búsqueda). Se extrae en una carpeta temporal fuera de G: y se copia a `prototipo\` sin `memoria\`,
  `dist\` ni `figures\` (105 archivos).
- Búsqueda local de nombres reales y de secretos en la base: sin coincidencias de nombres; las contraseñas de
  Jenkins son variables de entorno (`${JENKINS_ADMIN_PASSWORD}`).

### Pruebas existentes (sin cambios en el código)
| Suite | Comando | Resultado |
|:---|:---|:---|
| Compilación del kit | `javac -Xlint:all -Werror ...` (JDK 21) | OK, sin advertencias |
| Kit, JUnit 5 | `java -jar junit-platform-console-standalone-1.11.4.jar execute --select-package org.tfg.kit` | **16 de 17**. Falla `emptyMailEscapesSchemaButNotRules` |
| Kit, JUnit 5, con `-Duser.language=en -Duser.country=US` | Igual | **17 de 17** |
| Plano agéntico, pytest (Python 3.12.15) | `.venv/Scripts/python -m pytest -q` | **15 de 15** en 22,2 s |
| Banco de defectos | `python -m tfg_agents.evaluation --docs 300` | 22 mutantes: esquema 5 (23 %), ejecución 19 (86 %), CI 22 (100 %), falsos positivos 0 en los tres modos |
| Consola JavaFX | `javac --module-path openjfx-21 --add-modules javafx.controls ...` | Compila |

**Causa del fallo del kit**: `XsdValidator` clasifica un error del XSD como D4 si el mensaje contiene
`is not complete`. Con la configuración regional `es`, JAXP devuelve los mensajes traducidos y la
clasificación falla. Se corregirá en la Fase 1 clasificando por el código `cvc-complex-type.2.4.*`, que no
depende del idioma.

**Otro hallazgo**: `pip install -e agentic-plane` falla porque `pyproject.toml` no declara los paquetes
(setuptools detecta varias carpetas en la raíz). En la Fase 0 se instalan las dependencias directamente; se
corregirá en la Fase 4.

### Control de versiones
- `.gitignore` de la raíz: se añaden `!prototipo/` y `!prototipo/**` al final.
- `prototipo\.gitignore`: excluye artefactos, entornos, cachés, bases de datos, registros, datos generados,
  secretos, editores, temporales y archivos de asistentes de IA (estos últimos porque `!prototipo/**`
  volvería a incluirlos).
- `git check-ignore -v --no-index`: 22 rutas de prueba excluidas y 7 rutas de código incluidas, como se esperaba.

### Decisiones del autor y seguridad (antes del push de la Fase 0)
- S-01 aceptada (formatos del documento), proveedor Gamma (S-07), desviaciones D-03 a D-05 registradas.
- Revisión automática de seguridad: la ruta `POST /api/proposals/{pid}/decision` no exige autenticación y toma
  el revisor del cuerpo (H-01). Se buscó el mismo patrón en la base: también en los dos clientes de revisión
  (H-02); además, la validación XSD antes del rechazo del DOCTYPE (H-03) y el servidor del kit escuchando en
  todas las interfaces (H-04). Todo registrado en `docs/SEGURIDAD.md` como pendiente.
- `README.md`: advertencia al inicio (datos sintéticos, sin autenticación real, no apto para producción).

## Fase 1. Núcleo compartido (2026-10-19)

### Kit de verificación
- `XsdValidator` clasifica los errores del XSD por el código `cvc-...` y el nombre del elemento, no por el texto
  traducido. Nueva prueba `XsdLocaleTest` (5 casos, con `es-CO` y `en-US` dentro de la propia prueba).
- `pom.xml` del kit: la suite completa se ejecuta dos veces, con `-Duser.language=es` y `-Duser.language=en`.
- H-03: el DOCTYPE se rechaza antes de cualquier análisis, también en modo solo esquema (regla `R-E0-03`);
  validación XSD con `FEATURE_SECURE_PROCESSING` y sin acceso externo.
- H-04: `KitHttpServer` escucha por defecto en 127.0.0.1; `--bind 0.0.0.0` solo en la imagen de contenedor.
- `mvn install` del kit: **23 de 23 con `es` y 23 de 23 con `en`** (17 de la base + 5 de idioma + 1 de DOCTYPE).

### Plano de integración (`integration-plane`, Maven multimódulo)
| Módulo | Contenido | Pruebas |
|:---|:---|:---|
| `platform-contracts` | Canónico InvoiceRequest v1 (Java y JSON Schema 2020-12), 6 eventos + sobre, convención de topics, invariantes | 8 de 8 |
| `platform-core` | Bus (memoria y Kafka), `authorization_log` + historial (Flyway), idempotencia, correlación, estados, reintentos, auditoría, seguridad | 22 de 22; 1 omitida (`KafkaEventBusIT`: sin Docker) |
| `kit-adapter` | Kit como biblioteca, registro de perfiles (`alfa`, `beta`), `POST /api/kit/verify`, `GET /api/kit/profiles` | 5 de 5 |

Comando: `mvn install` en `integration-plane` (JDK 21) → `BUILD SUCCESS`, 5 módulos.

### Seguridad implementada en el núcleo
- Roles `REVIEWER`, `AGENT`, `OPERATOR`, `SYSTEM`; tokens de demostración aleatorios (32 bytes) generados al
  arrancar en `results/runtime/demo-tokens.json` (ignorado); en memoria solo su SHA-256.
- Denegar por defecto: todo excepto salud, OpenAPI y estáticos del front exige token; CORS limitado al origen
  del front; cabeceras CSP.
- Identidad siempre del token (`CurrentActor`); `IdentityGuard` rechaza con 403 un `reviewer` del cuerpo que no
  coincide. Pruebas: sin token 401, token inválido 401, agente/operador/sistema 403 en una decisión, la auditoría
  registra el sujeto del token, la suplantación en el cuerpo no deja rastro, las cabeceras editables no cambian
  la identidad.
- `SafeFiles`: nombres saneados y rutas confinadas (prueba con `../../etc/passwd`, `CON.json`, etc.).

### Plano agéntico
- `pyproject.toml`: `[build-system]` y `packages = ["tfg_agents"]`; `uv pip install -e ".[test]"` funciona.
- pytest: **15 de 15**. Banco de defectos tras los cambios del kit: 22 mutantes, esquema 5, ejecución 19, CI 22,
  0 falsos positivos (sin cambios).

## Fase 2. Servicios del plano de integración (2026-10-19)

### Módulos nuevos
| Módulo | Responsabilidad | Pruebas |
|:---|:---|:---|
| `provider-common` | Motor de mapeo Java (portado de `tfg_agents/mapping.py`), tabla de enrutamiento versionada (`data/routing/routing-table.v1.json`), preparador (mapeo vigente + kit en ejecución), cliente de proveedores y contrato `ProviderProtocol` | 6 de 6 |
| `svc-invoice-router` | `{pais}.invoice.request.v1` → `{pais}.{proveedor}.request.v1`; sin regla → DLQ | 2 de 2 |
| `svc-ingest-sftp` | Sondeo del buzón con Camel, carga por API, descompresión acotada, validación del canónico antes de procesar, idempotencia, inválidos a la DLQ | 4 de 4 |
| `svc-provider-adapter` | Kit antes de agrupar, lotes (alfa: ZIP por HTTP; beta: carpeta SFTP), reintentos con espera creciente, DLQ si el proveedor sigue caído | 3 de 3 |
| `svc-provider-connector` | Conector síncrono con estrategias por proveedor; kit antes de invocar | cubierto por la fachada |
| `svc-invoice-facade` | `POST /api/invoices` con idempotencia por clave de comprobante | 6 de 6 |
| `svc-response-processor` | Carpeta de retorno con Camel (fuente primaria), respuestas duplicadas, conciliación con la constancia oficial (respaldo, ADR-06) | 3 de 3 |
| `svc-response-publisher` | Respuesta al origen: JSONL por lote de origen; síncrono registrado | 1 de 1 |
| `svc-legal-archive` | Solo anexado, cadena de huellas SHA-256, retención por país, mejor esfuerzo con reintento (ADR-07) | 3 de 3 |
| `svc-invoice-registry` | Consulta, línea de tiempo, hallazgos con procedencia, auditoría y balance de conservación | 1 de 1 |
| `svc-dlq-handler` | Único dueño del estado DLQ; reproceso y descarte (solo OPERATOR, identidad del token); reintento controlado | 3 de 3 |
| `platform-app` | Aplicación Boot única; perfiles `local` y `full`; política de seguridad y flujo de extremo a extremo | 4 de 4 |

### Resultados
- **Conformidad del porte del motor de mapeo**: 30 casos exportados desde Python (18 generados + 12 fixtures).
  Beta: idéntico carácter a carácter. Alfa: hoja por hoja (kit en modo CI) y sin hallazgos en ejecución.
- `mvn install` del reactor completo (17 módulos): **118 pruebas, 0 fallos, 1 omitida** (`KafkaEventBusIT`, sin Docker).
- Prueba de humo del JAR (`--spring.profiles.active=local`): arranca en 10,5 s; `/actuator/health` UP; `/api/invoices/stats`
  401 sin token y 200 con el token de operador generado; OpenAPI agregado con 22 rutas.

### Defectos encontrados y corregidos durante la fase
- Archivo legal: si un intento escribía el objeto y fallaba antes de registrarlo, el reintento no terminaba nunca
  (`CREATE_NEW`). Ahora se reutiliza el objeto si su huella coincide y se rechaza si es distinto.
- Adaptador: carrera en las pruebas entre el vaciado periódico y la espera del bus. Se añade el contador de envíos en
  curso y `awaitIdle()`, que transfiere la propiedad del envío del vaciado al envío.

## Fase 3. Simuladores, generador de datos y registro de mapeos (2026-10-19)

### Módulos nuevos o ampliados
| Módulo | Responsabilidad | Pruebas |
|:---|:---|:---|
| `data-generator` | Comprobantes canónicos válidos con semilla, NIT, RUC y TIN sintéticos con dígito verificador válido, numeración por país (`data/numeracion/numeracion.csv`), defectos de datos D1 a D4, ejemplos validados; línea de órdenes (`generate`, `--mix`, `.jsonl`, `fixtures`, `defects`) | 4 de 4 |
| `kit-adapter` | Perfiles nuevos: `beta-pe` (catálogos SUNAT públicos en `catalogs/catalogos-beta-pe-v1.csv`) y `gamma` (esquema JSON y reglas G-D1 a G-D6 con procedencia, desde `specs/proveedor-gamma-v1.md`) | 5 de 5 |
| `provider-common` | Renderizador de Gamma, variante de Beta para PE, ejemplos validados (`data/fixtures/`), fuente de mapeos configurable (`veriagent.mappings.source`) | 8 de 8 |
| `svc-mapping-registry` | Versiones de mapeo (migración V3), control del kit (ejecución sobre 50 comprobantes sintéticos + integración continua sobre ejemplos validados), proponer, aprobar (409 si no pasó el kit), rechazar, activar con recarga en caliente por `platform.mapping.activated.v1`, revertir | 2 de 2 |
| `provider-simulators` | Alfa (sobre JSON y ZIP por HTTP), Beta (texto y carpeta SFTP con manifiesto) y Gamma (sobre JSON y ZIP); decisión determinista; constancias oficiales; fallos `unavailable`, `slow`, `duplicate`, `reject-catalog` y `lost-response` por API (OPERATOR) o cabecera | 5 de 5 |
| `platform-app` | Registro de mapeos como fuente (línea base alfa/CO y beta/PE), simuladores activos en el perfil local | 4 de 4 |
| `scripts/` | `run-local.ps1` (`-Build`, `-Clean`, `-Background`), `stop-local.ps1`, `lib/Veriagent.psm1`, `escenarios/S1.ps1`, `escenarios/S2.ps1` | ejecutados abajo |

### Resultados
- `mvn install` del reactor completo (19 módulos): **131 pruebas, 0 fallos, 1 omitida** (`KafkaEventBusIT`, sin Docker).
  El kit corre dos veces (configuración regional es y en): 23 + 23.
- Arranque real: `scripts
un-local.ps1 -Clean -Background` (perfil local, 127.0.0.1:8080).
- **S1** (`scripts\escenarios\S1.ps1`): comprobante de CO emitido por `POST /api/invoices` con el token de operador:
  HTTP 200, AUTHORIZED con número de autorización, reenvío idempotente (mismo número, `duplicate=true`), respuesta
  publicada, archivo legal con huella, balance de conservación correcto. Latencia de la llamada: 353 ms (primera
  emisión tras el arranque).
- **S2** (`scripts\escenarios\S2.ps1`): lote `.jsonl` de 1.000 comprobantes válidos (600 CO, 400 PE, semilla 202)
  cargado por `POST /api/ingest/batches`: 1.000 aceptados, 0 duplicados, 0 rechazados; 1.000 AUTHORIZED en 5 s
  (200 por segundo). Alfa recibió 7 lotes ZIP y Beta 4 lotes por carpeta SFTP.
- Conteo por estado al terminar S1 y S2: RECEIVED 0, PENDING_BATCH 0, SENT 0, AUTHORIZED 1.001, FAILED 0, DLQ 0.
  Por país y proveedor: CO/alfa 601, PE/beta 400. Conservación: registrados 1.001 = autorizados 1.001 + fallidos 0 +
  DLQ 0 + en curso 0. Cadena del archivo legal verificada: 1.001 entradas, válida.

### Defectos encontrados y corregidos durante la fase
- Registro de mapeos: la recarga en caliente se publicaba dentro de la transacción; un consumidor podía leer la
  versión anterior y dejarla en caché, y la reversión parecía no surtir efecto. Ahora la recarga y el evento se
  emiten después del commit.
- `run-local.ps1`: `Start-Process` no entrecomilla argumentos y la ruta del JAR tiene espacios. Se entrecomillan.

## Fase 3, correcciones posteriores (2026-10-19)
- H-05: quien propone una versión de mapeo no puede aprobarla (409). `MappingRegistryTest`: 3 de 3.
- RR-01: las rutas `/sim/` sin token solo valen con los simuladores habilitados explícitamente; `SimulatorExposureTest`
  comprueba que, desactivados, no hay rutas `/sim/` y responden 401. `platform-app`: 5 de 5.

## Fase 4a. Corrección H-01 y H-02 (2026-10-19)
- `agentic-plane/tfg_agents/security.py`: roles y tokens leídos del mismo archivo que genera la plataforma Java
  (`VERIAGENT_TOKENS_FILE` o `results/runtime/demo-tokens.json`), comparación por huella SHA-256 en tiempo constante.
- `review_api.py`: token obligatorio en todas las rutas de datos, decisión solo con rol REVIEWER, revisor tomado del
  token, `reviewer` del cuerpo solo si coincide (si no, 403), nueva ruta `GET /api/me`.
- Consolas web y JavaFX: token de acceso en lugar del campo "Revisor"; la consola JavaFX compila con OpenJFX 21.
- pytest (`.venv/Scripts/python -m pytest -q`, con Java 21 en el PATH para el kit): **20 de 20**.
- Comprobación con uvicorn real en 127.0.0.1: sin token 401; token de agente 403; suplantación en el cuerpo 403;
  revisor A 200 con `reviewer = reviewer-a`.
- Identidades de demostración: `reviewer-a` y `reviewer-b` (REVIEWER) sustituyen a la única identidad de revisor
  anterior (S-28). `mvn install` del reactor: **133 pruebas, 0 fallos, 1 omitida** (`KafkaEventBusIT`, sin Docker).

## Fase 4. Plano agéntico y ciclo cerrado (2026-10-20)

### Componentes (tabla 12)
| Componente | Implementación | Pruebas |
|:---|:---|:---|
| agent-orchestrator | `agent_orchestrator.py`: grafo de LangGraph 1.2.16 (instalado en esta fase): proponer, autocomprobar con el kit, reintentar (solo LLM), abrir solicitud de cambio, registrar la candidata y la propuesta | `test_cycle_*` |
| mapping-agent | Perfil Alfa (`mapping_agent.py`, base) y especificaciones nuevas (`spec_mapping_agent.py`): heurístico por defecto, LLM solo con `ANTHROPIC_API_KEY` | Gamma: 23 de 23 campos frente al mapeo de referencia |
| legacy-extraction-agent | `legacy_agent.py` sobre `data/legacy/pkg_fact_beta_pe.sql` (sintético), con archivo y línea | reglas idénticas a la línea base de Beta PE |
| dlq-triage-agent | `triage_agent.py`: taxonomía determinista, registra la recomendación por `POST /api/dlq/{id}/triage` | 4 casos de taxonomía |
| mcp-spec-reader, mcp-catalog, mcp-validator, mcp-repo | `mcp_servers.py` (cuatro servidores) | sin herramienta de merge; recorrido de rutas rechazado |
| Plataforma | `POST /api/mappings/{p}/{c}/check` (verificación en seco) y `POST /api/dlq/{id}/triage` | `MappingRegistryTest` 4 de 4, `DlqHandlerTest` 4 de 4 |

### Resultados
- pytest: **31 de 31** (con Java 21 en el PATH para el kit). Módulos Java afectados en verde; `platform-app` 5 de 5.
- **Ciclo cerrado en vivo** (perfil local, plataforma y API de revisión reales, tokens generados al arrancar):
  1. Comprobante de XX antes del mapeo: 422 `NO_MAPPING`; termina en DLQ.
  2. `run_cycle("gamma", "XX")`: el agente propone (`spec-heuristic-v1`), el kit lo verifica en seco (50 en
     ejecución, 6 ejemplos validados en CI, 0 fallos), abre `PR-0001` en el repositorio de juguete y registra la
     versión 1 como CANDIDATE; propuesta en `agent_proposal` como PENDING_REVIEW.
  3. El agente intenta aprobar en la plataforma: **403**. `reviewer-a` aprueba en la API de revisión: 200, reenviado
     al registro con su token (`approvedBy = reviewer-a`).
  4. El agente intenta activar: **403**. `reviewer-b` activa: 200 (`activatedBy = reviewer-b`).
  5. Dos comprobantes de XX después de activar: AUTHORIZED por gamma con el mapeo v1 (recarga en caliente).
  6. El agente de triaje clasifica la entrada de DLQ como `SIN_MAPEO_VIGENTE` → `ACTIVAR_MAPEO_Y_REPROCESAR`;
     el agente intenta reprocesar: **403**; la operadora reprocesa: 200. Conservación: 3 registrados = 3 autorizados.

## Fase 6. Escenarios, banco de defectos y mediciones (2026-10-20)

Orden de fases acordado con el autor: la Fase 6 se ejecuta antes que la 5. Comando único:
`scripts\escenarios\todos.ps1` (arranca la plataforma limpia, ejecuta S1 a S8, el banco y las mediciones y la
detiene): **todo en verde en 2,7 minutos**. Cada escenario tiene su propio script en `scripts\escenarios\` y deja
su resultado en `results\escenarios\<id>.json`. Los escenarios S3 a S8, el banco y el rendimiento están en
`agentic-plane/tfg_agents/scenarios.py` y hablan con la plataforma solo por HTTP, con los tokens de cada rol.

| Escenario | Comprobaciones | Resultado |
|:---|:---:|:---|
| S1 emisión síncrona | 7 de 7 | AUTHORIZED en 354 ms (primera emisión tras el arranque) |
| S2 lote de 1.000 | 5 de 5 | 1.000 AUTHORIZED en 5,7 s (174 por segundo); 600 CO por ZIP y 400 PE por carpeta SFTP |
| S3 defectos D1 a D6 | 9 de 9 | D1 y D4: 422 KIT_BLOCKED con regla, tipo, fuente y huella; D2 y D3: 400 en la entrada; D5 y D6: versión bloqueada y aprobación 409; 0 documentos defectuosos al proveedor |
| S4 rechazo del proveedor | 7 de 7 | CAT-01 → DLQ → triaje RECHAZO_CATALOGO_PROVEEDOR → agente 403 → operadora reprocesa → AUTHORIZED |
| S5 país XX y Gamma | 10 de 10 | agente propone desde la especificación, kit OK, PR abierta, agente 403 al aprobar y activar, reviewer-a aprueba, reviewer-b activa, XX autorizados con el mapeo nuevo |
| S6 idempotencia | 6 de 6 | reenvío síncrono con la misma autorización; reenvío del lote: 0 aceptados y 100 duplicados; respuesta duplicada del proveedor ignorada; 121 registrados nuevos exactos |
| S7 proveedor caído | 7 de 7 | Alfa no disponible: 50 CO en DLQ en 5,4 s, 30 PE autorizados; sin pérdidas; triaje PROVEEDOR_NO_DISPONIBLE; 50 reprocesos → AUTHORIZED |
| S8 reversión | 8 de 8 | versión del legado aprobada y activada, comprobante con la versión nueva, agente 403 al revertir, reviewer-a revierte, comprobante siguiente con la versión anterior, auditado |

**Banco de 22 defectos por el sistema real** (cada mutante propuesto al registro de mapeos con el token del agente):

| Tipo | Mutantes | Kit en ejecución (sistema) | Kit + CI (sistema) | Documento: ejecución / kit + CI |
|:---|:---:|:---:|:---:|:---:|
| D1 | 7 | 6 (86 %) | 7 (100 %) | 86 % / 100 % |
| D2 | 4 | 3 (75 %) | 4 (100 %) | 75 % / 100 % |
| D3 | 4 | 4 (100 %) | 4 (100 %) | 100 % / 100 % |
| D4 | 3 | 3 (100 %) | 3 (100 %) | 100 % / 100 % |
| D5 | 2 | 2 (100 %) | 2 (100 %) | 100 % / 100 % |
| D6 | 2 | 1 (50 %) | 2 (100 %) | 50 % / 100 % |
| **Total** | **22** | **19 (86 %)** | **22 (100 %)** | **86 % / 100 %** |

- Escapan al kit en ejecución los mismos tres mutantes que en el documento: D1-e (pasaporte como documento de
  extranjero, formatos idénticos), D2-a (cantidad y precio intercambiados, producto idéntico) y D6-b (nombre sin
  sanear: ninguno de los 50 comprobantes de la muestra trae el separador en el nombre). Los tres los detectan los
  ejemplos validados (CI).
- Falsos positivos: 0 (las líneas base de alfa/CO y beta/PE pasan el control: 50 de 50 en ejecución y 8 y 7
  ejemplos en CI). Los 22 mutantes bloqueados devuelven **409** al intentar aprobarlos.
- "Solo esquema" (23 % en el documento) no se mide por el sistema: ver D-06.

**Mediciones** (perfil local, un proceso, H2 en archivo, simuladores en el mismo proceso):

| Medida | Resultado | Umbral del documento (tabla 9) |
|:---|:---|:---|
| Verificación del kit por documento (400 documentos de Alfa y Beta) | p50 0,85 ms, p95 1,38 ms; por HTTP con token p50 19,5 ms, p95 39,1 ms | — |
| Emisión síncrona (200 comprobantes, incluye el simulador) | p50 30,4 ms, p95 52,4 ms, máximo 100,7 ms | p95 < 3 s sin contar el proveedor: **cumple** |
| Lote de 10.000 (6.000 CO y 4.000 PE) | 29,0 s de extremo a extremo (345 por segundo); aceptación de la carga 4,1 s; sin pérdidas | Día pico de 200.000: la tasa medida equivale a 200.000 en unos 10 minutos |

La proyección diaria es una extrapolación lineal de una corrida de 29 s en un solo proceso con simuladores
locales; no sustituye una prueba de carga sostenida ni mide Kafka ni PostgreSQL (perfil full no ejecutado).

## Fase 6b. Falsos positivos con n y banco ampliado (2026-10-20)

- `scenarios.py`: `falsos_positivos` (conteo por fuente con la cota superior del 95 %) y `banco_ampliado`
  (genera con `mutation_bank.py`, semilla 2026, hasta 30 por tipo, y verifica cada mutante por el registro).
  Scripts `scripts\escenarios\falsos_positivos.ps1` y `banco_ampliado.ps1`, incluidos en `todos.ps1`.
- Corrida de referencia: `todos.ps1` desde limpio, **todo en verde en 6,9 minutos**.
- Falsos positivos: **0 de 11.402** comprobantes correctos (S1 1, S2 1.000, S6 121, S7 80, latencia 200, lote
  10.000); cota superior del 95 %: 3/n = 0,026 %. Los 4 bloqueos del kit en el registro son los defectos
  intencionales de S3. 400 verificaciones directas del kit: 0 rechazos.
- Banco ampliado: espacio de 195 mutantes, 21 equivalentes excluidos (15 E2, 5 E1, 1 E3), 129 verificados.
  Kit en ejecución: 121 de 129 (93,8 %, IC Wilson 95 % 88,2 % a 96,8 %). Kit + ejemplos validados: 129 de 129
  (100 %, IC 97,1 % a 100 %). Por tipo, en `ESCENARIOS.md`. Huella del conjunto `37aa01b0...0534a`.
- Banco original de 22 en la misma corrida: sin cambios (19 de 22 en ejecución, 22 de 22 con ejemplos validados).

### Defectos encontrados y corregidos durante la fase
- En una primera corrida el escenario escribía `FALSOS_POSITIVOS.json` y luego lo "renombraba" a
  `falsos_positivos.json`; en Windows son el mismo archivo y el resultado se borraba. Ahora se escribe con el
  nombre final.
- En esa misma corrida la medición del lote salió en 285 s porque el conteo de estados (10.000 consultas) quedaba
  dentro del tiempo medido. Se movió fuera y se repitió la corrida completa; las cifras publicadas son de la
  corrida corregida (30,8 s).

## Fase 5. API de borde y front mínimo (2026-10-20)

- `svc-gateway`: `GET /api/session`; proxy `/agents/api/**` hacia la API de revisión (lista blanca: `health`, `me`,
  `proposals`, `proposals/{id}`, `proposals/{id}/decision`); front estático en `/`. `GatewayTest`: 3 de 3
  (sin token 401, mismo token reenviado, rutas fuera de la lista blanca rechazadas sin reenviar, CSP).
- `run-local.ps1 -Background` arranca también la API de revisión en 127.0.0.1:8090; `stop-local.ps1` detiene ambas.
- Front (HTML, CSS puro, JavaScript en módulos, sin dependencias, español, claro y oscuro según el sistema,
  navegación por teclado y enlace para saltar al contenido): Panel, Comprobantes, Emitir, Cargar lote, Cola DLQ,
  Propuestas de agentes y Verificador.
- **Prueba en navegador** (Chrome, perfil local con datos de S1, S2 y S5):
  - Con el token de `reviewer-a`: las siete pantallas cargan con datos reales; Emitir devuelve "Tu rol no permite
    esta operación" (403); Propuestas muestra la propuesta de Gamma aprobada por reviewer-a y los botones Revertir.
  - Con el token de `operador.demo`: Emitir → HTTP 200 AUTHORIZED con número de autorización; el detalle muestra el
    documento enviado al proveedor; el Verificador da "Sin datos" de hallazgos para ese documento y 1 hallazgo
    (R-D3-01, §2.1, huella y versión de la regla) al poner el perfil en minúsculas.
  - Ninguna pantalla muestra "null" ni "undefined"; 0 errores en la consola. Captura: `results/screens/front-panel.jpg`.
- Defectos encontrados en la prueba y corregidos: se pintaba "null" cuando una vista devolvía elementos nulos o
  arreglos anidados (el detalle no mostraba el documento enviado); se añadió `fill()` y el aplanado en `render()`.
- La consola JavaFX compila contra la nueva API (Fase 4a) y llega por `--api=http://127.0.0.1:8080/agents`; no se
  probó su interfaz gráfica en esta fase.
- `mvn install` del reactor (21 módulos): **138 pruebas, 0 fallos, 1 omitida** (`KafkaEventBusIT`, sin Docker). En
  una corrida falló de forma intermitente `ResponseProcessorTest.reconcilesWithReceipt` (gracia de 0 s frente a la
  precisión de la marca de tiempo guardada); la prueba reintenta ahora la conciliación durante 2 s como máximo.

## Fase 7. CI/CD y despliegue (2026-10-20)

### Cambios
- `ci/run-pipeline.sh`: etapas sobre Maven (`build`, `test-java`, `test-python`, `package`, `smoke`) y gates
  `gate-secrets` (claves, tokens, llaves privadas, tokens de demostración, correos no reservados, archivos de
  credenciales rastreados), `gate-deps` (sin SNAPSHOT ni rangos en los pom; dependencias de Python instaladas y
  conformes con `pyproject.toml`; trivy si está instalado), `gate-normative` (manifiesto `ci/normativa.sha256`
  de 8 documentos), `gate-bank` (banco de 22 sobre el kit, umbral de detección en ejecución) y `gate-mapping`.
  La prueba de humo arranca el JAR, comprueba 401 sin token y 200 con token, emite un comprobante (AUTHORIZED) y
  compara las huellas de las especificaciones que reporta el kit en ejecución con el manifiesto.
- `Jenkinsfile`: entorno de Python, compilación, gates previos en paralelo (secretos, huellas, dependencias),
  pruebas Java y Python, banco, mapeo, trivy (vulnerabilidades, secretos y configuración), empaquetado y humo,
  imágenes (plataforma y plano agéntico, con trivy de imagen), despliegue en SIT con Helm y aprobación manual para
  UAT y PROD.
- `deploy/docker/platform.Dockerfile` (nueva), `agentic.Dockerfile` (instala el paquete con `pyproject.toml`),
  `docker-compose.yml` del perfil full (Redpanda, PostgreSQL, plataforma con simuladores, API de revisión; puertos
  solo en 127.0.0.1; credenciales desde `.env`), `.env.example` con marcadores.
- Helm (ajuste mínimo): plantilla `platform.yaml`, imagen del plano agéntico renombrada, `VERIAGENT_URL` para los
  agentes, regla de red del plano agéntico hacia la plataforma y valores de la plataforma.

### Defecto encontrado y corregido: huella de la normativa distinta en Windows y en Linux
Git convertía a CRLF en la copia de trabajo de Windows tres especificaciones y dos catálogos creados en la Fase 3
(en el repositorio están en LF). La huella SHA-256 que el kit pone en cada hallazgo habría sido distinta en Linux.
`.gitattributes` fija ahora LF para `specs/`, `catalogs/`, `schemas/` y `data/`, y las copias locales se
convirtieron. Huellas que cambian en Windows: `proveedor-gamma-v1.md` (`41655837...` → `1c29b520...`),
`formato-beta-pe-v1.md`, los catálogos de Beta PE y Gamma y el esquema de Gamma. Los resultados de la Fase 6b se
regeneraron después de la corrección.

### Ejecutado
- `ci/run-pipeline.sh` con todas las etapas, en Git Bash sobre Windows: **todo en verde en 5 minutos**. JUnit del
  reactor completo, pytest 42 de 42, gate de secretos OK, dependencias OK, normativa 8 de 8, banco 22 mutantes
  (ejecución 19, 86 %; CI 22; 0 falsos positivos), mapeo vigente 112 documentos sin hallazgos, empaquetado, humo
  OK (emisión AUTHORIZED; huellas de alfa, beta, beta-pe y gamma iguales al manifiesto).
- `docker compose config` del perfil full: sintaxis válida (con un `.env` de prueba).

### No ejecutado, y por qué
- Jenkins: no hay servidor Jenkins en la máquina de trabajo; el Jenkinsfile no se ejecutó (sus etapas sí, por
  `run-pipeline.sh`).
- trivy (vulnerabilidades, secretos y configuración, en sistema de archivos y en imagen): no está instalado.
- Imágenes de contenedor y perfil full (`docker compose up`): el daemon de Docker no está disponible (S-09).
- Helm: ni `helm` ni un cluster disponibles; el chart no se validó con `helm lint` ni `helm template`.
- Consola JavaFX en el pipeline: OpenJFX no está en la ruta por defecto del script (`FX_LIB`); se compiló aparte.
- Tras la corrección de LF se repitió `todos.ps1` completo desde limpio: todo en verde en 7,1 minutos, con los
  mismos conteos (S1 a S8, banco 19 y 22 de 22, falsos positivos 0 de 11.402, banco ampliado 121 y 129 de 129 con
  la misma huella del conjunto). Es la corrida de referencia de `ESCENARIOS.md`; `banco_ampliado.json` registra ya la
  huella LF de `formato-beta-pe-v1.md`.

## Fase 8. Documentación (2026-10-20)

- `README.md` del prototipo reescrito: arquitectura y ciclo gobernado en mermaid, inicio rápido, resultados
  principales, estructura y enlaces a la documentación.
- `docs/TRAZABILIDAD.md`: 48 filas (10 requisitos, 8 escenarios de calidad, 10 ADR, 11 servicios y 9 componentes
  agénticos) con implementación, pruebas, evidencia y estado: 40 cumplen, 6 parciales, 1 no implementado
  (ADR-02, arquetipo) y 1 no evaluado (disponibilidad mensual).
- `DESVIACIONES.md`: D-12 a D-16 (PDF y constancia en el archivo, arquetipo, Schema Registry, plantillas y pruebas
  del agente, búsqueda por correlación). `ESCENARIOS.md`, `SUPUESTOS.md` y `SEGURIDAD.md` revisados.
- `.gitattributes`: LF también para `ci/normativa.sha256`, para que el gate de huellas funcione en una copia de
  Windows.
- El README de la raíz del repositorio no se modificó: el bloque propuesto se presenta al autor para su aprobación.

## Fase 9. Tareas de cierre T1 a T6 (2026-10-20)

Un commit por tarea. Los resultados de cada tarea se resumen en `docs/RESULTADOS_PARA_DOCUMENTO.md`.

### T4. Fase del paquete 7.2 en la EDT
- `planning/wbs.json`: el paquete 7.2 (Banco de defectos por inyección) pasa de F6 a F5, como en la tabla de la EDT
  de la Entrega 3.
- Antes del cambio se ejecutó `planning/model.py` (Python 3.14.3, NumPy 2.4.2) en sus cuatro configuraciones (base,
  `scen-alto`, `scen-bajo` y `whatif-qa2` con `TFG_CAP_OVERRIDE={"QA":2}`): las cuatro reproducen exactamente los
  `results.json` versionados.
- Después del cambio, en las cuatro configuraciones: el único campo de `results.json` que cambia es la fase de 7.2
  en la lista `tareas`. Plazo, costo, percentiles, reserva, criticidad, ruta crítica y carga por rol son idénticos;
  `load.json` y las muestras Monte Carlo (`mc_total.npy`, `mc_cost.npy`, 10.000 iteraciones) son idénticos byte a
  byte. Base: 40,2 semanas, COP 1.013.168.365 determinístico, P80 42,2 semanas y COP 1.059.219.670. El modelo no usa
  la fase en ningún cálculo; solo la copia a la salida.
- Se regeneraron los cuatro `results.json` (solo cambia esa línea).

### T1. Gate de huellas de la normativa en CI
- `ci/gate-normative.sh` (nuevo): recalcula el SHA-256 de cada especificación, catálogo y esquema (`specs/*.md`,
  `catalogs/*.csv`, `schemas/*`) y lo compara con el manifiesto versionado `ci/normativa.sha256`. Falla si una
  huella difiere, si falta un documento del manifiesto o si hay un documento normativo que el manifiesto no lista.
  La etapa `gate-normative` de `ci/run-pipeline.sh` lo invoca; el Jenkinsfile la ejecuta en los gates previos.
- `ci/test-gate-normative.sh` (nuevo, etapa `test-gate-normative`, también en Jenkins antes del gate): trabaja sobre
  una copia temporal y no toca el repositorio.
- Ejecutado: `bash ci/run-pipeline.sh gate-normative test-gate-normative` en Git Bash. Gate: 8 de 8 documentos OK.
  Prueba: 8 casos correctos de 8: el estado limpio pasa (dos veces) y fallan un espacio añadido a una
  especificación, una fila alterada en un catálogo, un tipo cambiado en un esquema, finales de línea CRLF, un
  catálogo borrado y un catálogo nuevo sin declarar.
- No ejecutado: el Jenkinsfile en un servidor Jenkins (no hay servidor).
