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
