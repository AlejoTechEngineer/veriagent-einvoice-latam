# ADR-07. Archivo legal asíncrono de mejor esfuerzo con reintento

Estado: aceptada · Fecha: 2026-10

## Contexto
El archivo legal (PDF, XML y constancia) es obligatorio, pero no debe bloquear la actualización del estado del comprobante.

## Opciones consideradas
- **Archivo síncrono bloqueante**: Consistencia inmediata; un fallo del almacenamiento detiene la emisión.
- **Archivo asíncrono con reintento y alerta (elegida)**: Disponibilidad del flujo principal; consistencia eventual controlada.

## Decisión
El archivado ocurre de forma asíncrona, con reintentos, cuarentena en DLQ y alerta si se supera el umbral. El almacenamiento es inmutable (WORM) con retención por país.

## Consecuencias
Positivas: el flujo principal no depende del almacenamiento. Negativas: ventana corta de consistencia eventual que debe monitorearse.

## Escenario de calidad que la justifica
Disponibilidad: una caída del almacenamiento de 30 minutos no detiene la emisión y se recupera sin pérdida.

## Evidencia en la prueba de concepto
Fuera del alcance de la PoC; se valida en la fase F3.
