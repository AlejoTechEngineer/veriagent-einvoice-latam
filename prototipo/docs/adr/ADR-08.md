# ADR-08. Schema Registry con compatibilidad BACKWARD

Estado: aceptada · Fecha: 2026-10

## Contexto
El modelo canónico evolucionará (por ejemplo, el correo del adquirente pasó a ser obligatorio). Los consumidores no se despliegan todos a la vez.

## Opciones consideradas
- **Contratos sin registro**: Libertad total; roturas silenciosas entre servicios.
- **Registro con compatibilidad BACKWARD (elegida)**: Evolución segura; disciplina en los cambios de esquema.

## Decisión
El modelo canónico InvoiceRequest se registra en Schema Registry con compatibilidad BACKWARD; los campos nuevos nacen opcionales con valor por defecto.

## Consecuencias
Positivas: los consumidores antiguos siguen funcionando. Negativas: un campo obligatorio requiere un proceso de dos versiones.

## Escenario de calidad que la justifica
Mantenibilidad: un cambio del canónico no rompe a ningún consumidor desplegado.

## Evidencia en la prueba de concepto
El defecto D4 de la PoC reproduce justamente un campo obligatorio que el canónico antiguo no traía.
