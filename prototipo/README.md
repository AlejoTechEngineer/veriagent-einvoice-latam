# TFG · Arquitectura agéntica verificable para facturación electrónica multipaís

> **Advertencia.** Este es un prototipo académico que trabaja únicamente con datos sintéticos. No tiene
> autenticación real (solo tokens de demostración) y no es apto para producción. Los hallazgos de seguridad
> conocidos y su estado están en [docs/SEGURIDAD.md](docs/SEGURIDAD.md).

Prueba de concepto, modelo de planificación y fuentes de la memoria del Trabajo Fin de Grado
(Ingeniería Informática, UNIR). Todo el contenido es sintético: la especificación "Perfil Alfa v2.1",
el formato "Beta" y los comprobantes no corresponden a ninguna organización, proveedor ni autoridad real.

## Qué demuestra

| Pieza | Evidencia |
|---|---|
| Kit de verificación (Java 21, solo JDK) | Reglas D1–D6 con procedencia (sección + SHA-256 de la norma), XSD, fixtures de CI, CLI y HTTP |
| Banco de defectos | 22 mutantes: esquema 23 %, kit en ejecución 86 %, kit + CI 100 %, 0 falsos positivos |
| Plano agéntico (Python) | Agente de mapeo (heurístico y LLM), orquestador gobernado, registro `agent_proposal`, servidor MCP 2.x |
| Revisión humana | API (409 si se aprueba algo bloqueado), consola web en HTML+CSS puro y consola JavaFX |
| CI/CD | `Jenkinsfile` + Jenkins como código (JCasC), Dockerfiles, docker-compose y chart de Helm |
| Planificación | PERT + nivelación de recursos + Monte Carlo (10.000 iteraciones), presupuesto y punto de equilibrio |

## Requisitos

JDK 21, Python 3.12, `junit-platform-console-standalone` (JUnit 5) y, para la consola de escritorio, OpenJFX.
Dependencias de Python: `pip install pydantic lxml fastapi uvicorn httpx "mcp>=2" anthropic pytest hypothesis numpy matplotlib graphviz`.

## Uso

```bash
ci/run-pipeline.sh                          # compilar, probar, gates, empaquetar y prueba de humo
JUNIT_JAR=/ruta/junit-platform-console-standalone.jar ci/run-pipeline.sh test-java

cd agentic-plane
python3 -m tfg_agents.evaluation            # banco de defectos -> results/evaluation.{json,md}
python3 -m tfg_agents.demo_seed             # dos propuestas de ejemplo (una bloqueada, una pendiente)
uvicorn tfg_agents.review_api:app --host 127.0.0.1 --port 8090   # API + consola web; exige token (ver docs/SEGURIDAD.md)
python3 -m tfg_agents.mcp_server            # servidor MCP por stdio
ANTHROPIC_API_KEY=... python3 -m tfg_agents.orchestrator   # agente con LLM real

# Consola JavaFX
cd review-console-javafx
javac --module-path $FX --add-modules javafx.controls -d build/classes $(find src/main/java -name '*.java')
cp -r src/main/resources/* build/classes/
java --module-path $FX --add-modules javafx.controls -cp build/classes org.tfg.console.ReviewConsoleApp --api=http://127.0.0.1:8080/agents

# Planificación
cd planning && python3 model.py
TFG_CAP_OVERRIDE='{"QA":2}' TFG_PLAN_OUT=whatif-qa2 python3 model.py   # escenario con dos QA

# Jenkins listo para usar (http://localhost:8080)
export JENKINS_ADMIN_PASSWORD=... JENKINS_REVIEWER_PASSWORD=...
docker compose -f ci/jenkins/docker-compose.yml up -d --build

# Entorno local con contenedores (kit en 8085, API y consola en 8090)
ci/run-pipeline.sh build package && docker compose up --build

# Memoria en Word (requiere Node con docx y LibreOffice)
cd memoria && python3 build.py              # genera out/memoria.docx con índices paginados
```

## Estructura

```
specs/ catalogs/ schemas/   especificación sintética, catálogos versionados y XSD
verification-kit/           kit en Java 21 + pruebas JUnit 5
agentic-plane/              plano agéntico + pruebas pytest/Hypothesis
review-console-web/         consola en HTML y CSS puro
review-console-javafx/      consola de escritorio JavaFX (CSS de JavaFX)
planning/                   EDT, supuestos y modelo de planificación
ci/ Jenkinsfile             script de etapas y Jenkins como código
deploy/ docker-compose.yml  Dockerfiles y chart de Helm
docs/adr/                   diez decisiones arquitectónicas
figures/ results/           figuras, resultados, capturas y log del pipeline
memoria/                    contenido y generador de la memoria en Word
```
