# Trazabilidad: documento → prototipo → evidencia

Cada fila enlaza un elemento de la Entrega 3 con el código que lo implementa, las pruebas automáticas que lo
comprueban y la evidencia de ejecución real (escenarios de las Fases 6 y 6b, en `results/escenarios/`). Estados:
**Cumple** (implementado y comprobado en ejecución), **Parcial** (implementado en parte; la diferencia está en
`DESVIACIONES.md`), **No implementado** y **No evaluado** (no medible en el prototipo).

## Resumen

| Elemento del documento | Filas | Cumple | Parcial | No implementado | No evaluado |
|:---|:---:|:---:|:---:|:---:|:---:|
| Requisitos funcionales (tabla 8) | 10 | 7 | 3 | 0 | 0 |
| Escenarios de calidad (tabla 9) | 8 | 5 | 2 | 0 | 1 |
| Decisiones arquitectónicas (ADR-01 a ADR-10) | 10 | 8 | 1 | 1 | 0 |
| Servicios del plano de integración (tabla 11, más el registro de mapeos) | 11 | 11 | 0 | 0 | 0 |
| Componentes del plano agéntico (tabla 12) | 9 | 9 | 0 | 0 | 0 |
| **Total** | **48** | **40** | **6** | **1** | **1** |

## Requisitos funcionales (tabla 8)

| ID | Requisito | Implementación | Pruebas | Evidencia | Estado |
|:---|:---|:---|:---|:---|:---|
| RF-01 | Recibir por SFTP y REST, validar el canónico con JSON Schema y publicar por país | `svc-ingest-sftp`, `svc-invoice-facade`, `platform-contracts` (esquemas versionados), topics `{pais}.invoice.request.v1` | `IngestTest`, `InvoiceFacadeTest`, `ContractTest` | S1 (REST), S2 (lote de 1.000), S3 (D2 y D3 rechazados en la entrada) | Cumple |
| RF-02 | Enrutar por país, tipo de documento y tabla versionada | `svc-invoice-router`, `data/routing/routing-table.v1.json` | `InvoiceRouterTest`, `RoutingAndClientTest` | S2 (CO→Alfa, PE→Beta), S5 (XX→Gamma) | Cumple |
| RF-03 | Transformar y verificar con el kit antes de enviar | `provider-common` (`DocumentPreparer`), `svc-provider-adapter` (antes de agrupar), `svc-provider-connector` (antes de invocar), `kit-adapter` | `ProviderAdapterTest`, `InvoiceFacadeTest`, `RendererConformanceTest` | S3: 0 documentos defectuosos al proveedor; falsos positivos 0 de 11.402 | Cumple |
| RF-04 | Respuestas con fuente primaria y respaldo, y actualizar el estado | `svc-response-processor` (carpeta de retorno, conciliación con constancias) | `ResponseProcessorTest`, `ProviderSimulatorTest.headerFaults` | S2, S6 (respuesta duplicada ignorada), S7 | Cumple |
| RF-05 | Archivar PDF, XML y constancia con inmutabilidad y retención por país | `svc-legal-archive`: documento transformado, cadena de huellas SHA-256, retención por país | `LegalArchiveTest` | S1 (archivo con huella); cadena verificada | Parcial (sin PDF ni constancia en el archivo: D-12) |
| RF-06 | Consultar estado y auditoría por identificador de correlación | `svc-invoice-registry`: detalle, línea de tiempo, hallazgos y auditoría por clave del comprobante; la correlación se muestra en el detalle | `InvoiceRegistryTest` | Pantalla Comprobantes del front | Parcial (sin búsqueda por correlación: D-16) |
| RF-07 | Propuesta de mapeo, plantillas y pruebas como solicitud de cambio | `agent_orchestrator.py`, `spec_mapping_agent.py`, `mapping_agent.py`, `mcp-repo` | `test_cycle_*`, `test_spec_agent_proposes_the_gamma_mapping_from_the_spec` | S5 (Gamma: 23 de 23 campos, solicitud de cambio sin merge) | Parcial (solo mapeo y verificación, sin plantillas ni pruebas: D-15) |
| RF-08 | Extraer reglas de código heredado con archivo y línea | `legacy_agent.py`, `data/legacy/pkg_fact_beta_pe.sql` | `test_legacy_agent_recovers_rules_with_file_and_line` | S8 (versión del legado aprobada, activada y revertida) | Cumple |
| RF-09 | Clasificar la DLQ según D1 a D6 y proponer la corrección | `triage_agent.py`, `POST /api/dlq/{id}/triage` | `test_triage_taxonomy`, `DlqHandlerTest.triageIsOnlyARecommendation` | S4 (rechazo de catálogo), S5 (sin mapeo), S7 (proveedor caído) | Cumple |
| RF-10 | Registrar cada propuesta con verificación, revisor y decisión; impedir aprobar lo que no pasó el kit | `agent_proposal`, `review_api.py`, registro de mapeos (409) | `test_review_api.py`, `MappingRegistryTest` | Banco original: 22 de 22 bloqueados y 409; banco ampliado: 129 de 129 | Cumple |

## Escenarios de calidad (tabla 9)

| Atributo | Medida del documento | Evidencia en el prototipo | Estado |
|:---|:---|:---|:---|
| Eficiencia: día pico | 200.000 comprobantes sin degradar el flujo síncrono | Lote de 10.000 en 22,9 s (437 por segundo) y p95 síncrono de 54 ms en la misma corrida; extrapolación lineal de un solo proceso, sin Kafka ni PostgreSQL | Parcial |
| Eficiencia: solicitud síncrona | p95 < 3 s sin el proveedor | p95 54,3 ms con 200 emisiones (incluye el simulador) | Cumple |
| Fiabilidad: operación mensual | 99,9 % de disponibilidad | No medible en un prototipo local | No evaluado |
| Fiabilidad: reproceso o duplicado | Cero pérdidas y cero duplicados | S6 (121 nuevos exactos, 0 duplicados), S7 (sin pérdidas con el proveedor caído), conservación en todos los escenarios | Cumple |
| Adecuación funcional | 100 % pasa XSD y reglas antes del envío | S3 (ningún defectuoso al proveedor), bancos (100 % con ejemplos validados), 0 falsos positivos de 11.402 | Cumple |
| Mantenibilidad: nuevo proveedor | Incorporación con arquetipo y agente en una fracción del esfuerzo manual | S5: Gamma incorporado con el agente y el kit sin código nuevo de adaptador; el esfuerzo frente a una línea base manual no se midió y no hay arquetipo (D-13) | Parcial |
| Seguridad | Rechazo de ZIP malicioso, XXE y secretos; agentes con mínimo privilegio | `IngestTest.zipLimits`, `ProviderSimulatorTest.zipTraversal`, `rejectsDoctype*`, gate de secretos; agentes 403 al aprobar, activar, revertir y reprocesar (S4, S5, S8) | Cumple |
| Seguridad (responsabilidad) | Trazable hasta su fuente normativa, su versión y su aprobador | Cada hallazgo con regla, sección, huella y versión (S3); `mapping_version` con quién propuso, aprobó y activó; `agent_proposal` y `audit_log` con la identidad del token | Cumple |

## Decisiones arquitectónicas

| ADR | Decisión | Implementación | Evidencia | Estado |
|:---|:---|:---|:---|:---|
| ADR-01 | Topics por país y proveedor | `Topics` en `platform-contracts`; `{pais}.invoice.*.v1` y `{pais}.{proveedor}.*.v1` | `ContractTest`; S7 (la caída de Alfa no afecta a Beta) | Cumple |
| ADR-02 | Adaptador por proveedor generado con arquetipo | No hay arquetipo Maven: un adaptador común con estrategias por proveedor y mapeos como datos | — | No implementado (D-13) |
| ADR-03 | Python para el plano agéntico | `agentic-plane` (Python 3.12, LangGraph 1.2.16, MCP 2.3.0) | pytest 42 de 42 | Cumple |
| ADR-04 | Los agentes solo proponen mediante solicitud de cambio | `mcp-repo` sin merge; grafo sin nodos de decisión; token AGENT sin permisos de decisión | S5, S8: 403 al aprobar, activar y revertir | Cumple |
| ADR-05 | Verificación determinista en ejecución y en CI | Kit en adaptadores y conectores; control del registro (ejecución + ejemplos validados); gates del pipeline | S3, bancos, `gate-bank`, `gate-mapping` | Cumple |
| ADR-06 | Respuesta con fuente primaria y respaldo | Resultado del proveedor y conciliación con la constancia oficial | `ResponseProcessorTest.reconcilesWithReceipt`, fallo `lost-response` | Cumple |
| ADR-07 | Archivo legal asíncrono de mejor esfuerzo con reintento | `svc-legal-archive` con reintento en segundo plano | `LegalArchiveTest` | Cumple |
| ADR-08 | Schema Registry con compatibilidad BACKWARD | Esquemas JSON versionados en `platform-contracts` y validación al publicar; sin Schema Registry | `ContractTest` | Parcial (D-14) |
| ADR-09 | Migraciones con Flyway | `V1__core`, `V2__services`, `V3__mapping_registry` (H2 y PostgreSQL) | todas las pruebas con base de datos | Cumple |
| ADR-10 | RAG con procedencia | `specs.py` y `mcp-spec-reader`: sección y huella SHA-256; manifiesto `ci/normativa.sha256` | `test_mcp_servers_are_least_privilege`, prueba de humo (huellas del kit = manifiesto) | Cumple |

## Servicios del plano de integración (tabla 11)

| # | Servicio | Módulo | Pruebas | Evidencia |
|:---|:---|:---|:---|:---|
| 1 | ingest-sftp | `svc-ingest-sftp` | `IngestTest` | S2, S3, S6, S7 |
| 2 | invoice-facade | `svc-invoice-facade` | `InvoiceFacadeTest` | S1, S3, S4, S5, S6, S8 |
| 3 | invoice-router | `svc-invoice-router` | `InvoiceRouterTest` | S2, S5, S7 |
| 4 | provider-adapter | `svc-provider-adapter` | `ProviderAdapterTest`, `BatchFlowTest` | S2, S7 |
| 5 | provider-connector | `svc-provider-connector` | `InvoiceFacadeTest` | S1, S4 |
| 6 | response-processor | `svc-response-processor` | `ResponseProcessorTest` | S2, S6 |
| 7 | response-publisher | `svc-response-publisher` | `ResponsePublisherTest` | S1 |
| 8 | legal-archive | `svc-legal-archive` | `LegalArchiveTest` | S1 |
| 9 | invoice-registry | `svc-invoice-registry` | `InvoiceRegistryTest` | todos (conservación) |
| 10 | dlq-handler | `svc-dlq-handler` | `DlqHandlerTest` | S4, S5, S7 |
| 11 | mapping-registry (añadido, D-04) | `svc-mapping-registry` | `MappingRegistryTest` | S3, S5, S8, bancos |

Además: `verification-kit` y `kit-adapter` (kit), `provider-simulators`, `svc-gateway` (API de borde y front),
`platform-core` (bus, estados, seguridad, auditoría) y `platform-app` (aplicación única, D-03).

## Componentes del plano agéntico (tabla 12)

| # | Componente | Implementación | Pruebas | Evidencia |
|:---|:---|:---|:---|:---|
| 11 | agent-orchestrator | `agent_orchestrator.py` (LangGraph) | `test_cycle_*` | S5, S8 |
| 12 | mapping-agent | `mapping_agent.py` (Alfa), `spec_mapping_agent.py` (especificaciones nuevas) | `test_agent.py`, `test_spec_agent_*` | S5 |
| 13 | legacy-extraction-agent | `legacy_agent.py` | `test_legacy_agent_*` | S8 |
| 14 | dlq-triage-agent | `triage_agent.py` | `test_triage_taxonomy` | S4, S5, S7 |
| 15 | mcp-spec-reader | `mcp_servers.py` (`spec_reader`) | `test_mcp_servers_are_least_privilege` | S5 |
| 16 | mcp-catalog | `mcp_servers.py` (`catalog`) | ídem | — |
| 17 | mcp-validator | `mcp_servers.py` (`validator`) → verificación en seco del registro | ídem, `MappingRegistryTest.dryRunCheck` | S5, S8 |
| 18 | mcp-repo | `mcp_servers.py` (`repo`), sin merge | ídem | S5, S8 (solicitudes de cambio abiertas) |
| 19 | eval-harness | `evaluation.py` (kit aislado), `scenarios.py` (`banco`, `banco_ampliado`, `falsos_positivos`), `mutation_bank.py` | `test_mutation_bank.py`, `gate-bank` | `BANCO.json`, `banco_ampliado.json`, `falsos_positivos.json` |

## Resultados de las Fases 6 y 6b usados como evidencia

| Medida | Valor | Archivo |
|:---|:---|:---|
| Escenarios S1 a S8 | 8 de 8 en verde (59 comprobaciones) | `S1.json` a `S8.json` |
| Banco original (22) por el sistema | ejecución 19 de 22 (86 %), con ejemplos validados 22 de 22 (100 %), 0 falsos positivos | `BANCO.json` |
| Banco ampliado (129, semilla 2026, 21 equivalentes excluidos) | ejecución 121 de 129 (93,8 %, IC 95 % 88,2–96,8 %), con ejemplos validados 129 de 129 (100 %, IC 97,1–100 %) | `banco_ampliado.json` |
| Falsos positivos | 0 de 11.402 (cota superior del 95 %: 0,026 %) | `falsos_positivos.json` |
| Latencias | kit p95 1,72 ms; emisión síncrona p95 54,3 ms | `RENDIMIENTO.json` |
| Lote | 10.000 en 22,9 s, sin pérdidas | `RENDIMIENTO.json` |
