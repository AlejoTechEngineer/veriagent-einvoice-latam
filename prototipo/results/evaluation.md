# Resultados del banco de defectos

22 mutantes sobre 300 comprobantes sintéticos y 12 fixtures; 14688 verificaciones en 14.37 s.

| Tipo | Mutantes | Solo esquema | Kit en ejecución | Kit + CI |
|---|---|---|---|---|
| D1 | 7 | 0 % | 86 % | 100 % |
| D2 | 4 | 0 % | 75 % | 100 % |
| D3 | 4 | 0 % | 100 % | 100 % |
| D4 | 3 | 67 % | 100 % | 100 % |
| D5 | 2 | 100 % | 100 % | 100 % |
| D6 | 2 | 50 % | 50 % | 100 % |
| **Total** | **22** | **23 %** | **86 %** | **100 %** |

Falsos positivos sobre salidas correctas: {'SCHEMA_ONLY': 0, 'RUNTIME': 0, 'CI': 0}

| Mutante | Descripción | Docs afectados | Esquema | Ejecución | CI | Detección por doc. (ejecución) |
|---|---|---|---|---|---|---|
| D1-a | Factura codificada como nota débito (92), código válido pero equivocado | 214 | no | sí | sí | 100 % |
| D1-b | Notas crédito codificadas como factura (01) | 56 | no | sí | sí | 100 % |
| D1-c | Código de tipo de documento inexistente (07) tomado de una nota verbal | 214 | no | sí | sí | 100 % |
| D1-d | Documento nacional (CC) codificado como empresa (A1) | 107 | no | sí | sí | 100 % |
| D1-e | Pasaporte codificado como documento de extranjero (A3); formatos idénticos | 35 | no | no | sí | 0 % |
| D1-f | IVA general (IVA19) codificado como IVA reducido (T02) | 272 | no | sí | sí | 100 % |
| D1-g | Beta: nota débito con código inexistente (93) | 30 | no | sí | sí | 100 % |
| D2-a | Cantidad y precio unitario intercambiados (producto idéntico) | 300 | no | no | sí | 0 % |
| D2-b | Precio unitario y valor de línea intercambiados | 295 | no | sí | sí | 100 % |
| D2-c | Total a pagar tomado del subtotal | 290 | no | sí | sí | 100 % |
| D2-d | Impuesto total tomado del subtotal | 300 | no | sí | sí | 100 % |
| D3-a | Identificador de perfil en minúsculas | 300 | no | sí | sí | 100 % |
| D3-b | Nombre del esquema tributario con espacio final | 272 | no | sí | sí | 100 % |
| D3-c | Nombre del esquema tributario en mayúscula inicial | 290 | no | sí | sí | 100 % |
| D3-d | Moneda en minúsculas | 300 | no | sí | sí | 100 % |
| D4-a | Correo del adquirente sin mapear (el canónico antiguo no lo traía) | 300 | sí | sí | sí | 100 % |
| D4-b | Correo del adquirente mapeado a un literal vacío | 300 | no | sí | sí | 100 % |
| D4-c | Fecha de emisión sin mapear | 300 | sí | sí | sí | 100 % |
| D5-a | Se envía LegacyOrderReference, retirado en v2 | 300 | sí | sí | sí | 100 % |
| D5-b | Se envía TaxExchangeRateLegacy, retirado en v2 | 300 | sí | sí | sí | 100 % |
| D6-a | Descripción sin sanear el separador reservado | 42 | sí | sí | sí | 100 % |
| D6-b | Nombre del adquirente sin sanear el separador reservado | 0 | no | no | sí | — |
