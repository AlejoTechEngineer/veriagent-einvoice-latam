# Especificación técnica sintética — Perfil Alfa v2.1 (País Alfa)

> Documento ficticio creado para la prueba de concepto del TFG. No corresponde a ninguna
> autoridad tributaria real. Imita la estructura de los anexos técnicos latinoamericanos
> basados en UBL 2.1: secciones numeradas, catálogos versionados y reglas de validación.

## §2 Generalidades

### §2.1 Identificación del perfil
El elemento `Invoice/ProfileID` DEBE contener exactamente el literal `PerfilAlfa-2.1`,
respetando mayúsculas y sin espacios.

### §2.2 Número y fecha
`Invoice/ID` es el número del comprobante. `Invoice/IssueDate` es la fecha de emisión en formato AAAA-MM-DD.

### §2.3 Moneda
`Invoice/DocumentCurrencyCode` DEBE pertenecer a la lista cerrada: COP, USD, PEN, MXN, en mayúsculas.

## §3 Tipos de documento

### §3.2 Catálogo TipoDocumento (versión 2)
| Código | Tipo |
|---|---|
| 01 | Factura de venta |
| 91 | Nota crédito |
| 92 | Nota débito |

### §3.4 Referencia al documento original
Las notas crédito (91) y débito (92) DEBEN incluir `Invoice/BillingReference` con el número del
documento que corrigen. Una factura (01) NO DEBE incluir `BillingReference`.

## §4 Partes

### §4.1 Catálogo TipoIdentificacion (versión 2)
| Código | Tipo | Formato |
|---|---|---|
| A1 | Identificación tributaria de empresa | 9 dígitos, guion, 1 dígito de verificación |
| A2 | Documento nacional de persona | 6 a 10 dígitos |
| A3 | Documento de extranjero | 6 a 12 caracteres alfanuméricos en mayúscula |
| A4 | Pasaporte | 6 a 12 caracteres alfanuméricos en mayúscula |

El atributo `schemeName` de `CompanyID` lleva el código del catálogo y el valor DEBE cumplir el formato del tipo.

### §4.3 Correo del adquirente
`Invoice/AccountingCustomerParty/Party/ElectronicMail` es OBLIGATORIO desde la versión 2 y no puede estar vacío.

## §5 Impuestos e importes

### §5.1 Catálogo Tributo (versión 2)
| Código | Tributo | Tarifa | Nombre del esquema |
|---|---|---|---|
| T01 | IVA general | 19.00 | IVA |
| T02 | IVA reducido | 5.00 | IVA |
| T03 | Exento | 0.00 | EXENTO |

`TaxCategory/Percent` DEBE coincidir con la tarifa del código. `TaxCategory/TaxSchemeName` DEBE ser exactamente el nombre del esquema.

### §5.3 Línea
Para cada línea: `InvoicedQuantity` × `PriceAmount` = `LineExtensionAmount` (tolerancia 0,01).
La cantidad DEBE ser mayor que cero y el precio no negativo.

### §5.4 Totales
`TaxTotal/TaxAmount` = Σ(`LineExtensionAmount` × `Percent` / 100) (tolerancia 0,01).
`LegalMonetaryTotal/LineExtensionAmount` = Σ de las líneas.
`LegalMonetaryTotal/PayableAmount` = `LineExtensionAmount` + `TaxAmount` (tolerancia 0,01).

## §6 Campos del comprobante (perfil Alfa v2.1)
| Ruta destino | Descripción | Tipo | Catálogo |
|---|---|---|---|
| Invoice/ProfileID | identificador literal del perfil | literal | — |
| Invoice/ID | número del comprobante emitido | texto | — |
| Invoice/IssueDate | fecha de emisión del comprobante | fecha | — |
| Invoice/InvoiceTypeCode | código del tipo de documento | código | TipoDocumento |
| Invoice/DocumentCurrencyCode | moneda del documento | código | — |
| Invoice/BillingReference | número del documento original corregido | texto | — |
| Invoice/AccountingSupplierParty/Party/PartyName | nombre o razón social del emisor vendedor | texto | — |
| Invoice/AccountingSupplierParty/Party/CompanyID | número de identificación tributaria del emisor | texto | — |
| Invoice/AccountingSupplierParty/Party/CompanyID/@schemeName | tipo de identificación del emisor | código | TipoIdentificacion |
| Invoice/AccountingCustomerParty/Party/PartyName | nombre del adquirente comprador cliente | texto | — |
| Invoice/AccountingCustomerParty/Party/CompanyID | número de identificación del adquirente cliente | texto | — |
| Invoice/AccountingCustomerParty/Party/CompanyID/@schemeName | tipo de identificación del adquirente cliente | código | TipoIdentificacion |
| Invoice/AccountingCustomerParty/Party/ElectronicMail | correo electrónico del adquirente cliente | texto | — |
| Invoice/TaxTotal/TaxAmount | valor total del impuesto | importe | — |
| Invoice/LegalMonetaryTotal/LineExtensionAmount | suma del valor de las líneas antes de impuestos | importe | — |
| Invoice/LegalMonetaryTotal/PayableAmount | valor total a pagar | importe | — |
| InvoiceLine/ID | consecutivo de la línea | entero | — |
| InvoiceLine/InvoicedQuantity | cantidad facturada de la línea | decimal | — |
| InvoiceLine/LineExtensionAmount | valor total de la línea antes de impuestos | importe | — |
| InvoiceLine/ItemDescription | descripción del artículo o servicio | texto | — |
| InvoiceLine/PriceAmount | precio unitario del artículo | importe | — |
| InvoiceLine/TaxCategory/ID | código del tributo aplicado a la línea | código | Tributo |
| InvoiceLine/TaxCategory/Percent | tarifa porcentual del tributo de la línea | decimal | Tributo |
| InvoiceLine/TaxCategory/TaxSchemeName | nombre del esquema tributario de la línea | literal | Tributo |

## §7 Elementos retirados en la versión 2
`Invoice/LegacyOrderReference` y `Invoice/TaxExchangeRateLegacy` existían en la versión 1 y NO DEBEN enviarse en la versión 2.

## §B Formato por segmentos del proveedor Beta

### §B.1 Estructura
Archivo de texto plano, un segmento por línea, campos separados por `|`. El separador es reservado:
ningún campo puede contenerlo; los textos libres DEBEN sanearse reemplazándolo por `/`.

### §B.2 Segmentos
| Segmento | Campos (en orden) | Número de campos |
|---|---|---|
| ENC | ENC, número, fecha, tipo documento, moneda, id emisor, id adquirente, nombre adquirente | 8 |
| DET | DET, línea, descripción, cantidad, precio, valor línea, código tributo | 7 |
| TOT | TOT, base, impuesto, total | 4 |
