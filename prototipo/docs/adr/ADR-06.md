# ADR-06. Respuesta con fuente primaria y respaldo

Estado: aceptada · Fecha: 2026-10

## Contexto
La respuesta del proveedor puede perderse o llegar incompleta. La constancia oficial de la autoridad también es recuperable.

## Opciones consideradas
- **Solo la respuesta del proveedor**: Simple; un fallo del proveedor deja comprobantes sin estado.
- **Fuente primaria y respaldo (elegida)**: Más resiliente; requiere correlación y reconciliación.
- **Consulta directa a la autoridad**: No siempre disponible y depende de cada país.

## Decisión
La respuesta del proveedor es la fuente primaria; la constancia oficial en UBL 2.1 es el respaldo. Ambas se correlacionan por correlationId y número de documento.

## Consecuencias
Positivas: menos comprobantes en estado incierto. Negativas: lógica de reconciliación y pruebas adicionales.

## Escenario de calidad que la justifica
Fiabilidad: ningún comprobante autorizado queda sin estado final tras la reconciliación diaria.

## Evidencia en la prueba de concepto
Fuera del alcance de la PoC; se valida en la fase F3.
