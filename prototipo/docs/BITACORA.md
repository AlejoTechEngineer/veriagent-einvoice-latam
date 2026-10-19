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
