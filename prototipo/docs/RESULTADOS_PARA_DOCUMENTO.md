# Resultados para el documento (Fase 9, tareas T1 a T6)

Resumen por tarea para trasladar al documento del TFG. Cada cifra sale de una ejecución real en la máquina de
trabajo (Windows 11, JDK 21.0.12, Python 3.12 del entorno del plano agéntico, Git Bash) el 2026-10-20. El detalle de
comandos y salidas está en `BITACORA.md`, Fase 9. Lo que no se pudo ejecutar se marca "no ejecutado".

Estados: **implementado y probado**, **parcial** (una parte probada y otra no ejecutada) o **no ejecutado**.

Totales al cierre de la Fase 9: reactor Java (`mvn -B verify`) 141 pruebas, 0 fallos, 0 errores, 1 omitida
(`KafkaEventBusIT`, sin Docker); Python (`pytest`) 57 de 57.

| Tarea | Estado | Commit |
|:---|:---|:---|
| T1. Gate de huellas de la normativa | Implementado y probado (Jenkins no ejecutado) | `d3076b8` |
| T2. Salida del plano agéntico | Parcial: (a) implementado y probado; (b) escrito y con prueba estática, no ejecutado en cluster | `51d8bb7` |
| T3. Auditoría por muestreo (A-10) | Implementado y probado | `462b976`, `784e3c2` |
| T4. Paquete 7.2 en F5 | Implementado y probado | `df52cea` |
| T5. Banco ampliado y falsos positivos | Implementado y probado por el sistema real | `0a52161` |
| T6. Este documento | Implementado | commit "Prototipo - Fase 9: resultados para el documento" |

## T1. Gate de huellas de la normativa en CI

- **Estado:** implementado y probado. No ejecutado: el Jenkinsfile en un servidor Jenkins (no hay servidor); sus
  etapas son las de `ci/run-pipeline.sh`, que sí se ejecutaron.
- **Qué hace:** `ci/gate-normative.sh` recalcula el SHA-256 de las 3 especificaciones, los 3 catálogos y los 2
  esquemas y lo compara con el manifiesto versionado `ci/normativa.sha256`. Falla si una huella difiere, si falta un
  documento del manifiesto o si aparece un documento normativo que el manifiesto no lista. Etapas
  `gate-normative` y `test-gate-normative` en `ci/run-pipeline.sh`; en el Jenkinsfile, en "Gates previos".
- **Prueba:** `ci/test-gate-normative.sh` (sobre una copia temporal; no toca el repositorio).
- **Números:** gate 8 de 8 documentos OK. Prueba: 8 de 8 casos correctos. El estado limpio pasa (2 casos) y el gate
  falla con cada una de las 6 alteraciones: un espacio en una especificación, una fila de catálogo, un tipo en un
  esquema, finales de línea CRLF, un catálogo borrado y un catálogo nuevo sin declarar.

## T2. Salida de red del plano agéntico

- **Estado:** parcial. (a) Implementado y probado. (b) Implementado en el chart y con una prueba estática, pero
  **no ejecutado** en un cluster: no hay helm, ni cluster, ni Docker. Tampoco se ejecutó `helm lint` ni
  `helm template`, ni se comprobó que el plugin de red aplique la política.
- **(a) Lista blanca en el cliente HTTP:** `agentic-plane/tfg_agents/egress.py`. Destinos permitidos (esquema, host
  y puerto exactos, tomados de la configuración del proceso): el kit (la plataforma `VERIAGENT_URL`, que en el
  prototipo lo sirve junto al registro de mapeos, y `TFG_KIT_URL` si existe) y el proveedor del modelo configurado
  (`ANTHROPIC_BASE_URL` o `https://api.anthropic.com`), solo si hay un modelo configurado. Se revisa cada petición,
  también las redirecciones, antes de conectar. La usan `PlatformClient`, `ReviewerForwarder`, `kit_client` y el
  SDK del modelo en los dos motores LLM.
- **Defecto encontrado por la prueba:** el SDK del modelo instalado (1.15.0) usa `httpx2` y rechazaba el cliente
  de httpx. Se corrigió con `egress.sdk_client()`.
- **Pruebas de (a):** `tests/test_egress.py`, 13 de 13: `test_kit_destination_allowed`,
  `test_standalone_kit_allowed_only_when_configured`, `test_other_destinations_rejected_before_connecting` (6
  casos: otro host, mismo host con otro puerto, otro esquema, `localhost`, metadatos de la nube y `file://`),
  `test_llm_provider_allowed_only_when_model_configured`, `test_redirect_outside_allowlist_rejected`,
  `test_platform_client_rejects_other_url`, `test_llm_sdk_client_goes_through_allowlist` y
  `test_llm_backends_build_sdk_with_allowlist`.
- **(b) Política de red en Helm (chart 0.3.0):** se eliminó la regla abierta al puerto 443. La salida del plano
  agéntico queda limitada a 4 destinos: el kit (8085), la plataforma (8080), el proxy de salida
  (`agentic.egress.proxy`, 3128) y el DNS del cluster (53). El despliegue define `HTTPS_PROXY` y `NO_PROXY`.
- **Prueba de (b):** `tests/test_helm_egress.py`, 2 de 2 (`test_agentic_egress_limited_to_kit_platform_proxy_and_dns`
  y `test_agentic_deployment_uses_proxy`). La plantilla se renderiza con un sustituto mínimo de Helm y la prueba
  comprueba que toda regla tiene un destino con selector de pods, sin bloques de IP ni puerto 443. No sustituye la
  validación en un cluster.

## T3. Auditoría por muestreo contra el sesgo de automatización (A-10, riesgo R6)

- **Estado:** implementado y probado.
- **Qué hace:** `POST /api/review-audits/sample` (solo OPERATOR o SYSTEM) sortea, entre las versiones de mapeo ya
  aprobadas por una persona, las que pasan a una cola de segunda revisión. La tasa es configurable (10 % por
  defecto) y la semilla también (si no se fija, se genera al azar). Selección reproducible con `java.util.Random(semilla)`
  sobre la población ordenada. Tasa, semilla, valor sorteado, quién muestreó y cuándo quedan registrados.
  `GET /api/review-audits` (REVIEWER, OPERATOR o SYSTEM). `POST /api/review-audits/{id}/decision` (solo
  REVIEWER): `CONFIRMED` o `DISPUTED`, con la identidad del token y por una persona distinta de quien aprobó (409 si
  es la misma). Los agentes reciben 403 en las tres operaciones.
- **Dónde se registra:** en `audit_log` (`REVIEW_AUDIT_SAMPLED`, `REVIEW_AUDIT_CONFIRMED` y
  `REVIEW_AUDIT_DISPUTED`) y en la tabla nueva `review_audit` (migración V4). **No en `authorization_log`**: en el
  documento esa tabla tiene una fila por comprobante, y una decisión de revisión no es un comprobante. Es el mismo
  criterio de S-12, registrado como S-36. Si el documento debe decir `authorization_log`, conviene precisar que el
  registro de decisiones es `audit_log`.
- **Pruebas:** `ReviewAuditTest`, 3 de 3:
  - `selectionIsReproducible`: con la misma semilla, la población en otro orden da la misma muestra y otra semilla
    da otra; con tasa del 10 % sobre 10.000 se seleccionan entre 900 y 1.100; con tasa 1 se selecciona todo.
  - `roles`: sin token, 401; el agente y el revisor no muestrean (403); el agente no consulta ni revisa (403); una
    tasa de 1,5 se rechaza con 400.
  - `sampleAndSecondReview`: la semilla 4242 queda registrada; la línea base no entra en la muestra y lo ya
    muestreado no vuelve a entrar; quien aprobó recibe 409 y una suplantación en el cuerpo, 403; otra persona
    registra DISPUTED con su identidad y una segunda decisión recibe 409.

  Además, `SecurityPolicyTest` cubre los dos endpoints nuevos.
- **Corrección posterior (`784e3c2`):** la revisión de seguridad detectó una condición de carrera: dos segundas
  revisiones simultáneas podían dejar en `audit_log` una decisión que no se aplicó. Ahora, si la actualización
  condicionada no cambia una fila, se devuelve 409 y no se registra nada. El módulo pasa sus 7 pruebas; no hay una
  prueba concurrente específica.
- **No incluido:** una pantalla del front para la cola. El front mínimo acordado tiene siete pantallas (D-09); la
  cola se usa por la API.

## T4. Paquete 7.2 de la EDT en la fase F5

- **Estado:** implementado y probado.
- **Cambio:** `planning/wbs.json`: 7.2 "Banco de defectos por inyección" pasa de F6 a F5, como en la Entrega 3.
- **Ejecución:** `planning/model.py` (Python 3.14.3, NumPy 2.4.2) en sus cuatro configuraciones: base,
  `scen-alto`, `scen-bajo` y `whatif-qa2`. Antes del cambio, las cuatro reproducen exactamente los `results.json`
  versionados.
- **Resultado: plazo y costo no cambian.** Después del cambio solo varía la fase de 7.2 en la lista `tareas`.
  Son idénticos todos los cálculos (semanas, costo, percentiles, reserva, criticidad, ruta crítica y carga por rol),
  además de `load.json` y las 10.000 muestras Monte Carlo (`mc_total.npy`, `mc_cost.npy`). El modelo no usa la fase
  en ningún cálculo.
- **Números del escenario base:**
  - Determinístico: 40,2 semanas y COP 1.013.168.365.
  - Monte Carlo: P50 de 40,2 semanas, P80 de 42,2 semanas y costo P80 de COP 1.059.219.670.
  - Reserva de contingencia: 2,0 semanas y COP 46.269.322.
  - Ruta crítica: 1.1 → 4.1 → 4.2 → 4.3 → 4.4 → 3.3 → 5.5 → 6.1 → 7.2 → 6.2 → 7.3.

  Se regeneraron los cuatro `results.json`; solo cambia esa línea.

## T5. Banco ampliado y falsos positivos por el sistema real

- **Estado:** implementado y probado por el sistema real (plataforma local, registro de mapeos y kit; perfil local
  sin Docker).
- **Ejecución:** `scripts\escenarios\todos.ps1` desde una plataforma limpia, con el código de la Fase 9: todo en
  verde en 8,3 minutos (S1 a S8, banco de 22, rendimiento, falsos positivos y banco ampliado). Resultados en
  `results/escenarios/fase9/`. Scripts: `agentic-plane/tfg_agents/mutation_bank.py` y `scenarios.py`; semilla
  2026; máximo 30 por tipo; kit 0.2.0; huella del conjunto
  `37aa01b0567298c686d985698272b0b055ec109078b2f19dba8841881fb0534a`. No se ajustó ningún operador ni ninguna
  regla.
- **Ground truth:** cada mutante se declara defectuoso. Los equivalentes se excluyen con un criterio escrito
  (`ESCENARIOS.md`): E1, el mapeo mutado es idéntico a la línea base; E2, el cambio solo afecta a claves o fuentes
  que el dominio del país de destino nunca produce; E3, el literal mutado coincide con el valor que la línea base ya
  produce en todo el dominio.

| Banco | Detectados en ejecución | Con ejemplos validados | Falsos positivos |
|:---|:---|:---|:---|
| Original de 22 (el del documento) | 19 de 22, 86,4 % (Wilson 95 %: 66,7–95,3 %) | 22 de 22, 100 % (85,1–100 %) | 0 (2 líneas base aceptadas) |
| Ampliado (129) | 121 de 129, 93,8 % (88,2–96,8 %) | 129 de 129, 100 % (97,1–100 %) | ver abajo |

| Tipo | Espacio | Equivalentes excluidos | Mutantes | En ejecución (Wilson 95 %) | Con ejemplos validados (Wilson 95 %) |
|:---|---:|:---|---:|:---|:---|
| D1 | 85 | 15 (E2) | 30 | 24, 80,0 % (62,7–90,5 %) | 30, 100 % (88,7–100 %) |
| D2 | 27 | 0 | 27 | 26, 96,3 % (81,7–99,3 %) | 27, 100 % (87,5–100 %) |
| D3 | 35 | 6 (5 E1, 1 E3) | 29 | 29, 100 % (88,3–100 %) | 29, 100 % (88,3–100 %) |
| D4 | 35 | 0 | 30 | 30, 100 % (88,7–100 %) | 30, 100 % (88,7–100 %) |
| D5 | 10 | 0 | 10 | 10, 100 % (72,3–100 %) | 10, 100 % (72,3–100 %) |
| D6 | 3 | 0 | 3 | 2, 66,7 % (20,8–93,9 %) | 3, 100 % (43,9–100 %) |
| **Total** | 195 | 21 | 129 | **121, 93,8 % (88,2–96,8 %)** | **129, 100 % (97,1–100 %)** |

- Solo D1 y D4 admiten 30 mutantes no equivalentes con los operadores existentes; D2, D3, D5 y D6 se quedan en
  27, 29, 10 y 3.
- **Escapan en ejecución 8 mutantes:** D1-x03, D1-x07, D1-x14, D1-x17, D1-x19, D1-x26, D2-x01 y D6-x02. Cinco de los
  de D1 son códigos válidos pero intercambiados en Beta PE. Todos los bloquean los ejemplos validados, y ninguno
  bloqueado se puede aprobar (409).
- **Falsos positivos: 0 de 11.402** comprobantes correctos procesados. Se cuentan S1 (1), S2 (1.000), S6 (121, sin
  reenvíos), S7 (80, los reprocesados una sola vez), la emisión síncrona de la medición (200) y el lote de 10.000.
  Cota superior del 95 % por la regla de tres: 3/n = 0,026 %. Los 4 bloqueos del kit en el registro son los
  defectos intencionales de S3. Además hubo 400 verificaciones directas del kit, con 0 rechazos.
- **Reproducibilidad:** los resultados son idénticos mutante por mutante a la Fase 6b, con la misma huella del
  conjunto.
- **Tiempos de esta corrida:** emisión síncrona p50 40,7 ms y p95 57,4 ms (umbral del documento: 3 s); lote de
  10.000 en 31,2 s (320 por segundo), sin pérdidas. En la corrida de referencia fueron p95 54,3 ms y 22,9 s; estos
  tiempos varían entre corridas.
- **Alcance:** datos sintéticos del generador (CO y PE). No es una estimación sobre datos reales.

## T6. Este documento

- **Estado:** implementado. Copia en `%USERPROFILE%\tfg_work\prisma_e2\RESULTADOS_PARA_DOCUMENTO.md` (fuera del
  repositorio).
