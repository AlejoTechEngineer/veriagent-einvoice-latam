# ADR-02. Adaptador por proveedor generado con arquetipo

Estado: aceptada · Fecha: 2026-10

## Contexto
Los formatos de los proveedores son heterogéneos (JSON, texto por segmentos, XML/UBL). Un transformador genérico configurable tiende a convertirse en un motor de reglas opaco.

## Opciones consideradas
- **Transformador genérico configurable**: Un solo componente; la configuración crece hasta ser un lenguaje propio difícil de probar.
- **Adaptador por proveedor desde arquetipo (elegida)**: Código explícito y probable por proveedor; el arquetipo fija estructura, pruebas y verificación.
- **iPaaS con mapeo visual**: Rápido al inicio; dependencia del fabricante y mapeo manual en cada país.

## Decisión
Cada proveedor tiene su adaptador generado con un arquetipo Maven que incluye el kit de verificación embebido y las pruebas de contrato.

## Consecuencias
Positivas: cada adaptador es pequeño, probable y desplegable por separado. Negativas: duplicación controlada entre adaptadores, que se reduce moviendo lo común al kit y a librerías compartidas.

## Escenario de calidad que la justifica
Mantenibilidad: incorporar un proveedor nuevo parte de un esqueleto que ya compila, pasa el kit y tiene su pipeline.

## Evidencia en la prueba de concepto
El transformador de la PoC separa el mapeo (datos) del motor de renderizado: el arquetipo empaqueta ambos.
