# ADR-10. RAG con procedencia para leer especificaciones

Estado: aceptada · Fecha: 2026-10

## Contexto
Las especificaciones son largas y cambian por versión. El agente necesita citar exactamente de dónde sale cada decisión.

## Opciones consideradas
- **Especificación completa en el prompt**: Simple; costoso, sin trazas y sin control de versión.
- **RAG con procedencia (elegida)**: Cada fragmento lleva sección y hash del documento; costo menor por consulta.
- **Ajuste fino del modelo**: Costoso y rígido ante cambios normativos.

## Decisión
El lector de especificaciones devuelve fragmentos con su sección y el SHA-256 del documento. Toda regla y toda propuesta guardan esa procedencia.

## Consecuencias
Positivas: trazabilidad regla → norma y auditoría de cambios normativos. Negativas: hay que reindexar cada versión de la norma.

## Escenario de calidad que la justifica
Seguridad (responsabilidad): cada regla y propuesta se rastrea hasta la sección y la versión exacta del documento que la origina.

## Evidencia en la prueba de concepto
El servidor MCP devuelve sección y huella; cada hallazgo del kit incluye fuente, sección, hash y versión de la regla.
