# Especificación sintética — Proveedor Gamma, formato JSON, país XX (v1)

> Documento ficticio del prototipo para el escenario S5 (incorporación de un país y un proveedor nuevos). Ni el
> proveedor Gamma ni el país XX existen. Es la especificación que el agente de mapeo lee para proponer el mapeo.

## §G.1 Documento
Cada comprobante es un objeto JSON con tres partes: `header`, `parties` y `lines`, más `totals`.
El campo `header/specVersion` DEBE contener exactamente el literal `GAMMA-1`, en mayúsculas.

## §G.2 Encabezado
| Ruta | Descripción | Tipo | Catálogo |
|---|---|---|---|
| /header/specVersion | literal de la versión de la especificación | literal | — |
| /header/number | número del comprobante emitido | texto | — |
| /header/issueDate | fecha de emisión del comprobante (AAAA-MM-DD) | fecha | — |
| /header/documentType | código del tipo de documento | código | TipoDocumento |
| /header/currency | moneda del documento, en mayúsculas | código | Moneda |
| /header/originalNumber | número del documento original que corrige una nota | texto | — |

### §G.2.1 Catálogo TipoDocumento
| Código | Tipo |
|---|---|
| FAC | Factura |
| NCR | Nota de crédito |
| NDB | Nota de débito |

Las notas (NCR, NDB) DEBEN incluir `/header/originalNumber`; una factura (FAC) NO DEBE incluirlo.

### §G.2.2 Catálogo Moneda
USD (dólar).

## §G.3 Partes
| Ruta | Descripción | Tipo | Catálogo |
|---|---|---|---|
| /parties/seller/name | nombre o razón social del vendedor emisor | texto | — |
| /parties/seller/taxId | número de identificación tributaria del vendedor emisor | texto | — |
| /parties/seller/idScheme | tipo de identificación del vendedor emisor | código | TipoIdentificacion |
| /parties/buyer/name | nombre del comprador adquirente cliente | texto | — |
| /parties/buyer/taxId | número de identificación del comprador cliente | texto | — |
| /parties/buyer/idScheme | tipo de identificación del comprador cliente | código | TipoIdentificacion |
| /parties/buyer/email | correo electrónico del comprador cliente (obligatorio, no vacío) | texto | — |

### §G.3.1 Catálogo TipoIdentificacion
| Código | Tipo | Formato |
|---|---|---|
| TX | Número de identificación tributaria | 10 dígitos con dígito de control (Luhn) |
| PP | Pasaporte | 6 a 12 caracteres alfanuméricos en mayúscula |

## §G.4 Líneas
| Ruta | Descripción | Tipo | Catálogo |
|---|---|---|---|
| /lines[]/lineNo | consecutivo de la línea | entero | — |
| /lines[]/description | descripción del artículo o servicio | texto | — |
| /lines[]/qty | cantidad facturada de la línea | decimal | — |
| /lines[]/unitPrice | precio unitario del artículo | importe | — |
| /lines[]/net | valor total de la línea antes de impuestos | importe | — |
| /lines[]/taxCode | código del impuesto aplicado a la línea | código | Impuesto |
| /lines[]/taxRate | tarifa porcentual del impuesto de la línea | decimal | Impuesto |

Para cada línea: `qty` × `unitPrice` = `net` (tolerancia 0,01).

### §G.4.1 Catálogo Impuesto
| Código | Impuesto | Tarifa |
|---|---|---|
| V12 | Impuesto al valor agregado general | 12.00 |
| V00 | Tasa cero | 0.00 |

## §G.5 Totales
| Ruta | Descripción | Tipo |
|---|---|---|
| /totals/net | suma del valor de las líneas antes de impuestos | importe |
| /totals/tax | valor total del impuesto | importe |
| /totals/gross | valor total a pagar | importe |

`/totals/net` = Σ `net`; `/totals/tax` = Σ (`net` × `taxRate` / 100); `/totals/gross` = `net` + `tax` (tolerancia 0,01).

## §G.6 Caracteres reservados
El carácter `|` está reservado por el sistema de reportes del proveedor: ningún texto libre puede contenerlo. Los
textos libres DEBEN sanearse reemplazándolo por `/`.

## §G.7 Campos retirados
El campo `/header/legacyRef` existía en la versión 0 y NO DEBE enviarse en la versión 1.
