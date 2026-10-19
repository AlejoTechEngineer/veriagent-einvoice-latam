# ADR-03. Python para el plano agéntico

Estado: aceptada · Fecha: 2026-10

## Contexto
El plano de integración es Java. Los agentes necesitan el ecosistema de LLM, MCP y orquestación de agentes, donde Python es la referencia.

## Opciones consideradas
- **Spring AI en Java**: Un solo lenguaje; ecosistema de agentes y MCP menos maduro.
- **Python con LangGraph y SDK de MCP (elegida)**: Ecosistema más maduro y documentado; dos lenguajes en el equipo.
- **TypeScript**: Buen SDK de MCP; menor afinidad con el equipo de datos e IA.

## Decisión
El plano agéntico se implementa en Python 3.12. La frontera con Java es el kit de verificación, expuesto por HTTP y por CLI.

## Consecuencias
Positivas: acceso directo al ecosistema de IA. Negativas: dos cadenas de herramientas; el contrato del kit debe ser estable y versionado.

## Escenario de calidad que la justifica
Mantenibilidad: el plano agéntico puede cambiar de proveedor de LLM sin tocar el plano de integración.

## Evidencia en la prueba de concepto
La PoC implementa el agente, el servidor MCP y la API en Python, e invoca el kit Java por CLI y HTTP.
