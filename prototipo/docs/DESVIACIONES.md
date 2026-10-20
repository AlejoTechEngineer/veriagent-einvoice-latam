# Desviaciones respecto al documento

Casos en los que el código obliga a apartarse de lo que dice la Entrega 3. El documento no se modifica: cada
desviación queda aquí para valorarla en la entrega final.

| ID | Apartado del documento | Documento | Prototipo | Motivo |
|:---|:---|:---|:---|:---|
| D-01 | 4.4.3 (tabla 14) | Base relacional SQL Server con Flyway | H2 en el perfil local y PostgreSQL en el perfil full, con Flyway | El encargo pide PostgreSQL para el perfil full y el perfil local debe correr sin Docker. |
| D-02 | 4.4.4 | Solo los topics de solicitud, respuesta y DLQ | Se añade `platform.mapping.activated.v1` | Necesario para la recarga en caliente de mapeos aprobados. |
| D-03 | 4.4.2 (tabla 11) | Un microservicio desplegable por pieza | Una biblioteca por servicio y una sola aplicación Spring Boot (`platform-app`) que activa los servicios por configuración | Permite el perfil local en un solo proceso y la misma imagen en el perfil full; la lógica sigue separada por servicio. |
| D-04 | 4.4.2 (tabla 11) | Diez microservicios en el plano de integración | Once: se añade `svc-mapping-registry` | El ciclo gobernado necesita publicar, activar y revertir versiones de mapeo aprobadas. |
| D-05 | 2.1 y 5.1 | Dos proveedores (Alfa y Beta) | Se añade el proveedor sintético Gamma (JSON, país XX) | Escenario S5: incorporación de un país y un proveedor nuevos. |
| D-06 | 5.3 (banco de defectos) | Tres mecanismos medidos sobre el kit aislado: solo esquema (23 %), kit en ejecución (86 %) y kit + CI (100 %) | Por el sistema real solo se miden dos: el control del registro de mapeos siempre corre el kit completo, así que "solo esquema" no existe como modo del sistema | La columna "solo esquema" sigue siendo la del kit aislado (`tfg_agents.evaluation`); el sistema no ofrece un modo degradado. |
| D-07 | 5.3 (banco de defectos) | 300 comprobantes sintéticos y 12 ejemplos validados por mutante | Control del registro: 50 comprobantes (semilla 99) en ejecución y los ejemplos validados del proveedor y el país (8 para alfa/CO, 7 para beta/PE) | Es el control que corre en cada propuesta; los porcentajes coinciden con el documento (ver `ESCENARIOS`). |
| D-08 | 5.3 (banco de defectos) | Los mutantes del formato por segmentos se aplican al mapeo de Beta del País Alfa | Se aplican a la línea base de Beta para PE (`beta-PE-v1.json`), que es el destino real de Beta en el prototipo | Mismos operadores de mutación (D1-g, D6-a, D6-b) sobre las mismas claves del mapeo. |
