# Especificación sintética — Formato por segmentos del proveedor Beta para Perú (v1)

> Documento ficticio del prototipo. El proveedor Beta y este perfil no existen. La estructura por segmentos es la
> misma que la del §B de `perfil-alfa-v2.1.md`; cambian los catálogos, que usan los códigos públicos de los
> catálogos del sistema de emisión electrónica de SUNAT (Resolución de Superintendencia 188-2010/SUNAT y sus
> anexos). La numeración de las secciones coincide con la del perfil base para que las reglas del verificador
> citen la sección correcta.

## §B Formato por segmentos

### §B.1 Estructura
Archivo de texto plano, un segmento por línea, campos separados por `|`. El separador es reservado: ningún campo
puede contenerlo; los textos libres DEBEN sanearse reemplazándolo por `/`. Un lote contiene uno o más comprobantes
consecutivos, cada uno con sus segmentos ENC, DET y TOT.

### §B.2 Segmentos
| Segmento | Campos (en orden) | Número de campos |
|---|---|---|
| ENC | ENC, número (serie-correlativo), fecha, tipo documento, moneda, RUC del emisor, documento del adquirente, nombre del adquirente | 8 |
| DET | DET, línea, descripción, cantidad, precio, valor línea, código tributo | 7 |
| TOT | TOT, base, impuesto, total | 4 |

## §3 Tipos de documento

### §3.2 Catálogo TipoDocumento (catálogo 01 de SUNAT)
| Código | Tipo |
|---|---|
| 01 | Factura |
| 07 | Nota de crédito |
| 08 | Nota de débito |

## §4 Partes

### §4.1 Catálogo TipoIdentificacion (catálogo 06 de SUNAT)
| Código | Tipo | Formato |
|---|---|---|
| 6 | Registro Único de Contribuyentes (RUC) | 11 dígitos con dígito de verificación |
| 1 | Documento Nacional de Identidad (DNI) | 8 dígitos |
| 4 | Carné de extranjería | 9 a 12 caracteres alfanuméricos |
| 7 | Pasaporte | 6 a 12 caracteres alfanuméricos |

## §5 Impuestos e importes

### §5.1 Catálogo Tributo (catálogo 05 de SUNAT)
| Código | Tributo | Tarifa |
|---|---|---|
| 1000 | IGV (impuesto general a las ventas) | 18.00 |
| 9997 | Exonerado | 0.00 |

### §5.3 Línea
Para cada línea: cantidad × precio = valor de línea (tolerancia 0,01).
