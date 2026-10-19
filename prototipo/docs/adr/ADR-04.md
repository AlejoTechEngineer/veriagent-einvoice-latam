# ADR-04. Los agentes solo proponen mediante pull request

Estado: aceptada · Fecha: 2026-10

## Contexto
Un LLM puede producir salidas plausibles e incorrectas. En facturación, una salida incorrecta es un documento rechazado o una contingencia fiscal.

## Opciones consideradas
- **Agentes con permiso de despliegue**: Máxima velocidad; responsabilidad difusa y riesgo inaceptable en un dominio regulado.
- **Agentes que proponen por pull request (elegida)**: Toda salida pasa por revisión y por el pipeline; añade latencia humana.
- **Sin agentes**: Elimina el riesgo de IA; no reduce el costo de incorporar países.

## Decisión
Los agentes solo crean ramas y pull requests. No tienen permiso de merge ni acceso a producción. Cada propuesta queda registrada en agent_proposal con su verificación, revisor y decisión.

## Consecuencias
Positivas: responsabilidad clara y trazabilidad completa. Negativas: la revisión humana es un cuello de botella, mitigado porque el kit filtra antes lo que no puede aprobarse.

## Escenario de calidad que la justifica
Seguridad (responsabilidad): cualquier cambio en producción se puede rastrear hasta su propuesta, su verificación y la persona que la aprobó.

## Evidencia en la prueba de concepto
La API de revisión impide aprobar una propuesta bloqueada por el kit (HTTP 409), comprobado en las pruebas automatizadas.
