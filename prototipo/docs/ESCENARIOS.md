# Escenarios, banco de defectos y mediciones

Todos los resultados de este documento salen de ejecuciones reales del prototipo en el perfil local (un proceso
Java con bus en memoria, H2 en archivo y simuladores de proveedores; plano agéntico en Python 3.12), con los
tokens de demostración generados al arrancar. El perfil full (Redpanda y PostgreSQL) no se ejecutó: Docker no
está disponible en la máquina de trabajo (S-09).

## Cómo se ejecutan

| Comando | Qué hace |
|:---|:---|
| `scripts\escenarios\todos.ps1` | Arranca la plataforma limpia, ejecuta S1 a S8, el banco de 22 defectos, el rendimiento, los falsos positivos y el banco ampliado, y la detiene |
| `scripts\escenarios\S1.ps1` ... `S8.ps1` | Un escenario (requiere la plataforma en marcha: `scripts\run-local.ps1 -Background`) |
| `scripts\escenarios\banco.ps1` | Banco original de 22 mutantes por el sistema real |
| `scripts\escenarios\rendimiento.ps1` | Latencia de verificación y de emisión, y lote de 10.000 |
| `scripts\escenarios\falsos_positivos.ps1` | Conteo de falsos positivos con n explícito (usa los resultados de la misma corrida) |
| `scripts\escenarios\banco_ampliado.ps1` | Banco ampliado generado con los operadores de D1 a D6 |

Cada escenario deja su resultado en `results/escenarios/`: `S1.json` a `S8.json`, `BANCO.json`, `RENDIMIENTO.json`,
`falsos_positivos.json` y `banco_ampliado.json`. S1 y S2 son scripts PowerShell; S3 a S8 y el resto están en
`agentic-plane/tfg_agents/scenarios.py` y hablan con la plataforma solo por HTTP, con el token de cada rol.

Corrida de referencia: `todos.ps1` completo desde una plataforma limpia, 7,1 minutos, código de salida 0
(2026-10-20, después de fijar LF en la normativa, Fase 7). Todas las cifras de este documento salen de esa misma
corrida.

## Escenarios S1 a S8

| ID | Escenario | Comprobaciones | Resultado |
|:---|:---|:---:|:---|
| S1 | Emisión síncrona correcta | 7 de 7 | AUTHORIZED en 265 ms (primera emisión tras el arranque) con número de autorización, respuesta publicada, archivo legal con huella; reenvío idempotente |
| S2 | Lote asíncrono de 1.000 comprobantes válidos | 5 de 5 | 1.000 AUTHORIZED en 4,7 s; 600 de CO por ZIP a Alfa y 400 de PE por carpeta SFTP a Beta; entradas = salidas + DLQ |
| S3 | Defectos D1 a D6 inyectados | 9 de 9 | D1 y D4: 422 KIT_BLOCKED con regla, tipo, fuente normativa y huella; D2 y D3: 400 en la entrada; D5 y D6 (mapeo): versión bloqueada y aprobación 409; ningún documento defectuoso llegó al proveedor |
| S4 | Rechazo del proveedor, DLQ, triaje, aprobación y reproceso | 7 de 7 | CAT-01 → DLQ → triaje RECHAZO_CATALOGO_PROVEEDOR → el agente no puede reprocesar (403) → la operadora reprocesa → AUTHORIZED |
| S5 | Incorporación del país XX con el proveedor Gamma | 10 de 10 | Sin mapeo el comprobante va a DLQ; el agente propone desde la especificación, el kit lo deja pasar y se abre una solicitud de cambio sin merge; el agente no puede aprobar ni activar (403); reviewer-a aprueba, reviewer-b activa; los comprobantes de XX se autorizan con el mapeo nuevo y el de la DLQ se reprocesa |
| S6 | Idempotencia ante envío duplicado | 6 de 6 | Reenvío síncrono: misma autorización; reenvío del lote: 0 aceptados y 100 duplicados; respuesta duplicada del proveedor ignorada; 121 registrados nuevos exactos |
| S7 | Proveedor caído | 7 de 7 | Con Alfa no disponible, los 50 de CO pasan a DLQ (recuperable) en 4,4 s y los 30 de PE se autorizan; sin pérdidas; triaje PROVEEDOR_NO_DISPONIBLE; tras la recuperación los 50 se reprocesan y se autorizan |
| S8 | Reversión de un mapeo | 8 de 8 | El agente de legado propone una versión de Beta PE; reviewer-a aprueba, reviewer-b activa; el agente no puede revertir (403); reviewer-a revierte; el comprobante siguiente usa la versión anterior; reversión auditada |

## Banco original de 22 defectos (el del documento)

Los 22 mutantes de `agentic-plane/tfg_agents/mutations.py`, sin cambios, propuestos uno a uno al registro de
mapeos con el token del agente. El control del registro corre el kit en ejecución sobre 50 comprobantes
sintéticos del país (semilla 99) y el kit en integración continua sobre los ejemplos validados (8 de alfa/CO y 7
de beta/PE). Resultado en `results/escenarios/BANCO.json`.

| Tipo | Mutantes | Kit en ejecución | Kit + ejemplos validados | Documento |
|:---|:---:|:---:|:---:|:---:|
| D1 | 7 | 6 (86 %) | 7 (100 %) | 86 % / 100 % |
| D2 | 4 | 3 (75 %) | 4 (100 %) | 75 % / 100 % |
| D3 | 4 | 4 (100 %) | 4 (100 %) | 100 % / 100 % |
| D4 | 3 | 3 (100 %) | 3 (100 %) | 100 % / 100 % |
| D5 | 2 | 2 (100 %) | 2 (100 %) | 100 % / 100 % |
| D6 | 2 | 1 (50 %) | 2 (100 %) | 50 % / 100 % |
| **Total** | **22** | **19 (86 %)** | **22 (100 %)** | **86 % / 100 %** |

- Escapan al kit en ejecución los mismos tres que en el documento: D1-e, D2-a y D6-b; los detectan los ejemplos
  validados. Los 22 bloqueados devuelven 409 al intentar aprobarlos. Líneas base: 0 falsos positivos.
- "Solo esquema" (23 % en el documento) no se mide por el sistema, que siempre corre el kit completo (D-06).

## Banco ampliado (Fase 6b)

Distinto del banco original: se genera con los mismos operadores de `mutations.py` (`_table`, `_beta_table`,
`_swap`, `_with`, `_drop`, `_add`, `_beta`), recorriendo el espacio de parámetros de cada tipo, con semilla 2026 y
hasta 30 mutantes por tipo. No se ajustó ningún operador ni regla. Cada mutante declara su verdad de base
(defectuoso, no equivalente). Generador: `agentic-plane/tfg_agents/mutation_bank.py`; ejecutor:
`scenarios.py` (`banco_ampliado`); script: `scripts/escenarios/banco_ampliado.ps1`. Kit de verificación 0.2.0.
Huella SHA-256 del conjunto: `37aa01b0567298c686d985698272b0b055ec109078b2f19dba8841881fb0534a`.

**Criterio de equivalencia** (los equivalentes se excluyen y se cuentan; un mutante no detectado nunca se
reclasifica como equivalente):
- E1. El mapeo mutado es idéntico a la línea base.
- E2. El cambio solo afecta a claves o fuentes que el dominio del país de destino nunca produce (por ejemplo, el
  emisor de CO siempre es NIT).
- E3. El literal mutado coincide con el valor que la línea base ya produce en todo el dominio (moneda "COP" en CO).

| Tipo | Espacio | Equivalentes excluidos | Mutantes | Kit en ejecución | IC Wilson 95 % | Kit + ejemplos validados | IC Wilson 95 % |
|:---|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| D1 | 85 | 15 (E2) | 30 | 24 (80,0 %) | 62,7 % – 90,5 % | 30 (100 %) | 88,7 % – 100 % |
| D2 | 27 | 0 | 27 | 26 (96,3 %) | 81,7 % – 99,3 % | 27 (100 %) | 87,5 % – 100 % |
| D3 | 35 | 6 (5 E1, 1 E3) | 29 | 29 (100 %) | 88,3 % – 100 % | 29 (100 %) | 88,3 % – 100 % |
| D4 | 35 | 0 | 30 | 30 (100 %) | 88,7 % – 100 % | 30 (100 %) | 88,7 % – 100 % |
| D5 | 10 | 0 | 10 | 10 (100 %) | 72,2 % – 100 % | 10 (100 %) | 72,2 % – 100 % |
| D6 | 3 | 0 | 3 | 2 (66,7 %) | 20,8 % – 93,9 % | 3 (100 %) | 43,9 % – 100 % |
| **Total** | **195** | **21** | **129** | **121 (93,8 %)** | **88,2 % – 96,8 %** | **129 (100 %)** | **97,1 % – 100 %** |

- Tipos que no admiten 30 variantes distintas con sus operadores: D2 (27: intercambios y cambios de fuente entre
  campos numéricos del mismo nivel), D3 (29 tras excluir equivalentes), D5 (10: la especificación solo retira dos
  elementos; las variantes difieren en el valor enviado) y D6 (3: el operador solo tiene dos indicadores de
  saneamiento y su combinación). D1 y D4 admiten más de 30 y se muestrean con la semilla.
- Escapan al kit en ejecución 8 mutantes, todos detectados por los ejemplos validados: cinco de Beta PE (códigos
  de tipo de documento o de impuesto válidos pero intercambiados: 9997 por IGV18, 08 por INVOICE, 08 y 01 por
  CREDIT_NOTE, 1000 por EXONERADO), uno de Alfa (CREDIT_NOTE como 92), cantidad y precio intercambiados (D2) y el
  nombre del adquirente sin sanear (D6, la muestra no trae el separador en el nombre). Las reglas de ejecución
  de Beta PE no cruzan el código del impuesto con la tarifa ni el tipo de documento con la referencia al
  original; se informa tal cual, sin cambiar reglas.
- Con muestras de 3 a 30 mutantes por tipo, los intervalos de Wilson son anchos (D6: 20,8 % a 93,9 %): las tasas
  por tipo son indicativas; la total (129) es la más estable.

## Falsos positivos con n explícito (Fase 6b)

Comprobantes correctos (sin contar reenvíos duplicados) que pasaron por el kit y cuántos bloqueó por error.
Resultado en `results/escenarios/falsos_positivos.json`, con la fuente de cada conteo.

| Fuente | Comprobantes correctos | Bloqueados por error |
|:---|:---:|:---:|
| S1 emisión síncrona | 1 | 0 |
| S2 lote de 1.000 | 1.000 | 0 |
| S6 idempotencia (sin reenvíos) | 121 | 0 |
| S7 proveedor caído (reprocesados una vez) | 80 | 0 |
| Medición de latencia síncrona | 200 | 0 |
| Medición del lote | 10.000 | 0 |
| **Total** | **11.402** | **0** |

- **0 de 11.402.** Cota superior del 95 % de la tasa de falsos positivos: 3/n = 0,026 % (regla de tres; la cota
  exacta, 1 − 0,05^(1/n), da el mismo valor con seis decimales).
- En todo el registro, el kit bloqueó 4 comprobantes y los 4 son los defectos intencionales de S3.
- Además, 400 verificaciones directas del kit sobre documentos ya autorizados (medición de latencia): 0 rechazos.
  No suman comprobantes nuevos.
- Alcance: datos sintéticos del generador para CO y PE (y XX en S5, no incluido en el conteo pedido); no es una
  estimación sobre datos reales.

## Mediciones frente a la tabla 9 del documento

| Medida | Resultado | Umbral |
|:---|:---|:---|
| Verificación del kit por documento (400) | p50 0,66 ms, p95 1,72 ms; por HTTP con token p50 31,1 ms, p95 45,7 ms | — |
| Emisión síncrona (200, incluye el simulador) | p50 42,9 ms, p95 54,3 ms, máximo 93,9 ms | p95 < 3 s: cumple |
| Lote de 10.000 (6.000 CO y 4.000 PE) | 22,9 s de extremo a extremo (437 por segundo), aceptación de la carga 3,5 s, 10.000 AUTHORIZED, sin pérdidas | 200.000 en el día pico: la tasa medida los procesa en unos 8 minutos |

La capacidad diaria es una extrapolación lineal de una corrida corta en un solo proceso con simuladores locales;
no sustituye una prueba de carga sostenida ni mide Kafka ni PostgreSQL. Las cifras de la corrida de referencia
están en `RENDIMIENTO.json`. Entre corridas el lote de S2 (1.000) osciló entre 102 y 213 comprobantes por segundo
(213 en la de referencia) y el de 10.000 entre 290 y 437.
