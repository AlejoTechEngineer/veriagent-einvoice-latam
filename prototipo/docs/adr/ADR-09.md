# ADR-09. Migraciones de base de datos con Flyway

Estado: aceptada · Fecha: 2026-10

## Contexto
Cada adaptador persiste estado y auditoría. Los entornos SIT, UAT y PROD deben tener el mismo esquema.

## Opciones consideradas
- **Scripts manuales**: Sin herramienta; deriva entre entornos y errores de orden.
- **Flyway versionado en el repositorio (elegida)**: Reproducible y auditable; disciplina de versiones.

## Decisión
Cada servicio versiona sus migraciones con Flyway; el pipeline las aplica antes del despliegue.

## Consecuencias
Positivas: entornos reproducibles. Negativas: las migraciones destructivas exigen un procedimiento de dos pasos.

## Escenario de calidad que la justifica
Mantenibilidad: cualquier entorno se reconstruye desde cero con el mismo esquema.

## Evidencia en la prueba de concepto
La PoC usa SQLite con esquema declarado en código para agent_proposal; en el proyecto se migra con Flyway.
