# ADR-01. Topics de Kafka por país y proveedor

Estado: aceptada · Fecha: 2026-10

## Contexto
Cada país tiene volúmenes, ventanas de cierre y proveedores distintos. Un fallo del proveedor de un país no puede detener la emisión de los demás.

## Opciones consideradas
- **Cola única con filtrado por cabecera**: Simple de operar; un consumidor lento o un mensaje envenenado afecta a todos los países.
- **Topic por país y proveedor (elegida)**: Aislamiento de fallos, escalado y retención por país; más topics que gobernar.
- **Colas por tipo de documento**: Aísla por tipo, no por país; no resuelve el aislamiento de proveedores.

## Decisión
Se usan topics con la convención invoice.<país>.<proveedor>.<evento>, con particionado por número de documento para conservar el orden por comprobante.

## Consecuencias
Positivas: un incidente queda contenido en su país; la retención y las réplicas se ajustan por volumen. Negativas: más topics y ACL que administrar, mitigado con plantillas de infraestructura como código.

## Escenario de calidad que la justifica
Fiabilidad: un proveedor caído durante 2 horas no degrada la emisión de los otros países (0 mensajes perdidos, latencia del resto sin cambio).

## Evidencia en la prueba de concepto
Fuera del alcance de la PoC (no incluye Kafka); se valida en la fase F1 del proyecto planificado.
