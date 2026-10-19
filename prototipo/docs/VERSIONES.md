# Versiones

Versiones del apartado 4.4.3 (Stack tecnológico) del documento y las elegidas en el prototipo. Cuando una
versión del documento no existe, no está instalada o no es compatible, se elige la estable más cercana.

| Pieza | Documento | Elegida | Motivo |
|:---|:---|:---|:---|
| Java | Java 21 (LTS) | Eclipse Temurin 21.0.12 | La máquina solo tiene JDK 17 y 8; se usa un JDK 21 portable en `%USERPROFILE%\tools`, sin tocar el sistema. |
| Python | Python 3.12 | CPython 3.12.15 | La máquina tiene 3.14 y 3.13; se instala 3.12 portable con `uv` en `%USERPROFILE%\tools\python`. |
| Spring Boot | Spring Boot 4 | 4.1.1 | Última estable de la línea 4. |
| Apache Camel | Apache Camel 4 | 4.23.0 | Su `camel-dependencies` declara Spring Boot 4.1.1. |
| springdoc-openapi | No se indica | 3.1.1 | Línea 3.x, compatible con Spring Boot 4. |
| Kafka | Kafka con Schema Registry | Redpanda v26.2.5 (perfil full) | Compatible con el protocolo de Kafka y sin ZooKeeper; los esquemas se versionan como JSON Schema en el código. |
| PostgreSQL | SQL Server; PostgreSQL con pgvector para RAG | PostgreSQL 18 (perfil full); H2 2.x (perfil local) | Ver `DESVIACIONES.md`. |
| JUnit | JUnit 5 | Kit: JUnit Jupiter 5.11.4 (Maven y consola 1.11.4). Plano de integración: JUnit Jupiter 6.0.3 (gestionada por Spring Boot 4.1.1) | JUnit 6 es la sucesora directa de JUnit 5 que trae Spring Boot 4; el kit conserva JUnit 5. |
| Jackson | Jackson | 3.1.5 (gestionada por Spring Boot 4.1.1) | Spring Boot 4 usa Jackson 3 (`tools.jackson`). |
| JSON Schema | Avro o JSON Schema en Schema Registry | networknt json-schema-validator 3.0.8 (JSON Schema 2020-12) | Usa Jackson 3, igual que Spring Boot 4. |
| Flyway | Flyway | 12.4.0 (gestionada por Spring Boot 4.1.1) + `flyway-database-postgresql` | |
| H2 / controlador PostgreSQL | — | 2.4.240 / 42.7.13 (gestionadas por Spring Boot 4.1.1) | |
| Cliente de Kafka | Kafka | kafka-clients 4.2.1 (gestionada por Spring Boot 4.1.1) | Se usa el cliente directamente, sin Spring Kafka. |
| Testcontainers | Testcontainers | 2.0.5 (`testcontainers-redpanda`) | Las pruebas que lo usan se omiten si no hay Docker. |
| LangGraph | LangGraph | 1.2.16 | Última estable. |
| SDK de MCP | MCP 2.x | 2.3.0 | Última estable. |
| FastAPI, Pydantic, lxml | Sí | 0.148.0, 2.14.1, 6.1.3 | Últimas estables. |
| SDK de Anthropic | Sí | 1.15.0 | El motor LLM solo se activa con `ANTHROPIC_API_KEY`. |
| JavaFX | JavaFX | OpenJFX 21.0.6 | Módulos de Maven Central para Windows, portables. |
| Resilience4j | Resilience4j | `resilience4j-retry` 2.4.0 (núcleo, sin starter) | Existe `resilience4j-spring-boot4` 2.4.0, pero solo se necesita el reintento con espera exponencial; el módulo núcleo evita autoconfiguraciones. |
