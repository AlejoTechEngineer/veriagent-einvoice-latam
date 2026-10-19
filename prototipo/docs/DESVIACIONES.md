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
