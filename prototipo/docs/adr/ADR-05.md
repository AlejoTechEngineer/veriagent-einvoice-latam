# ADR-05. Verificación determinista como gate en ejecución y en CI

Estado: aceptada · Fecha: 2026-10

## Contexto
La revisión humana por sí sola no detecta de forma fiable códigos válidos pero equivocados ni intercambios de campos. Se necesita un verificador que no dependa del juicio del modelo.

## Opciones consideradas
- **Revisión humana como único control**: Sin inversión inicial; detección irregular y no medible.
- **Solo validación XSD**: Barata; en la PoC detecta el 23 % de los mutantes.
- **Kit en ejecución + fixtures en CI (elegida)**: En la PoC detecta el 86 % en ejecución y el 100 % con CI, sin falsos positivos.

## Decisión
El kit se embebe en cada adaptador (verificación en ejecución) y se ejecuta como gate obligatorio en el pipeline con fixtures validados por personas.

## Consecuencias
Positivas: control objetivo, medible y trazable a la norma. Negativas: hay que mantener reglas y fixtures por versión normativa; se asume como parte del costo de cada país.

## Escenario de calidad que la justifica
Adecuación funcional: el 100 % de los comprobantes pasa XSD y reglas antes de salir; ninguna propuesta que falle el kit puede aprobarse.

## Evidencia en la prueba de concepto
Banco de 22 mutantes: esquema 23 %, kit en ejecución 86 %, kit + CI 100 %; 0 falsos positivos en 1.224 verificaciones correctas.
