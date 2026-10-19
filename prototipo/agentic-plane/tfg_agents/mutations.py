"""Banco de defectos por inyección: un operador de mutación por variante de la taxonomía D1 a D6.

Cada mutante es una especificación de mapeo defectuosa, como la que podría proponer un agente o una
persona. Se mide qué mecanismo de verificación la detecta (apartado 3.5 de la memoria).
"""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Callable

from .mapping import clone

Mapping = dict[str, Any]


@dataclass(frozen=True)
class Mutant:
    id: str
    type: str          # D1..D6
    format: str        # "xml" (perfil Alfa) o "seg" (proveedor Beta)
    description: str
    apply: Callable[[Mapping], Mapping]


def _entry(m: Mapping, target: str) -> dict:
    return next(e for e in m["entries"] if e["target"] == target)


def _with(target: str, fn: Callable[[dict], None]) -> Callable[[Mapping], Mapping]:
    def op(m: Mapping) -> Mapping:
        c = clone(m)
        fn(_entry(c, target))
        return c
    return op


def _drop(target: str) -> Callable[[Mapping], Mapping]:
    def op(m: Mapping) -> Mapping:
        c = clone(m)
        c["entries"] = [e for e in c["entries"] if e["target"] != target]
        return c
    return op


def _swap(t1: str, t2: str) -> Callable[[Mapping], Mapping]:
    def op(m: Mapping) -> Mapping:
        c = clone(m)
        a, b = _entry(c, t1), _entry(c, t2)
        a["source"], b["source"] = b["source"], a["source"]
        return c
    return op


def _add(entry: dict) -> Callable[[Mapping], Mapping]:
    def op(m: Mapping) -> Mapping:
        c = clone(m)
        c["entries"].append(entry)
        return c
    return op


def _table(target: str, key: str, value: str) -> Callable[[Mapping], Mapping]:
    return _with(target, lambda e: e["table"].__setitem__(key, value))


def _beta(key: str, value: Any) -> Callable[[Mapping], Mapping]:
    def op(m: Mapping) -> Mapping:
        c = clone(m)
        c[key] = value
        return c
    return op


def _beta_table(table: str, key: str, value: str) -> Callable[[Mapping], Mapping]:
    def op(m: Mapping) -> Mapping:
        c = clone(m)
        c[table][key] = value
        return c
    return op


TC = "InvoiceLine/TaxCategory"
CUST = "Invoice/AccountingCustomerParty/Party"

MUTANTS: list[Mutant] = [
    # D1 — código de catálogo incorrecto
    Mutant("D1-a", "D1", "xml", "Factura codificada como nota débito (92), código válido pero equivocado",
           _table("Invoice/InvoiceTypeCode", "INVOICE", "92")),
    Mutant("D1-b", "D1", "xml", "Notas crédito codificadas como factura (01)",
           _table("Invoice/InvoiceTypeCode", "CREDIT_NOTE", "01")),
    Mutant("D1-c", "D1", "xml", "Código de tipo de documento inexistente (07) tomado de una nota verbal",
           _table("Invoice/InvoiceTypeCode", "INVOICE", "07")),
    Mutant("D1-d", "D1", "xml", "Documento nacional (CC) codificado como empresa (A1)",
           _table(f"{CUST}/CompanyID/@schemeName", "CC", "A1")),
    Mutant("D1-e", "D1", "xml", "Pasaporte codificado como documento de extranjero (A3); formatos idénticos",
           _table(f"{CUST}/CompanyID/@schemeName", "PAS", "A3")),
    Mutant("D1-f", "D1", "xml", "IVA general (IVA19) codificado como IVA reducido (T02)",
           _table(f"{TC}/ID", "IVA19", "T02")),
    Mutant("D1-g", "D1", "seg", "Beta: nota débito con código inexistente (93)",
           _beta_table("docTypeTable", "DEBIT_NOTE", "93")),
    # D2 — campos intercambiados
    Mutant("D2-a", "D2", "xml", "Cantidad y precio unitario intercambiados (producto idéntico)",
           _swap("InvoiceLine/InvoicedQuantity", "InvoiceLine/PriceAmount")),
    Mutant("D2-b", "D2", "xml", "Precio unitario y valor de línea intercambiados",
           _swap("InvoiceLine/PriceAmount", "InvoiceLine/LineExtensionAmount")),
    Mutant("D2-c", "D2", "xml", "Total a pagar tomado del subtotal",
           _with("Invoice/LegalMonetaryTotal/PayableAmount", lambda e: e.__setitem__("source", "totals.baseAmount"))),
    Mutant("D2-d", "D2", "xml", "Impuesto total tomado del subtotal",
           _swap("Invoice/TaxTotal/TaxAmount", "Invoice/LegalMonetaryTotal/LineExtensionAmount")),
    # D3 — literal mal formateado
    Mutant("D3-a", "D3", "xml", "Identificador de perfil en minúsculas",
           _with("Invoice/ProfileID", lambda e: e.__setitem__("value", "perfilalfa-2.1"))),
    Mutant("D3-b", "D3", "xml", "Nombre del esquema tributario con espacio final",
           _with(f"{TC}/TaxSchemeName", lambda e: e["table"].__setitem__("IVA19", "IVA "))),
    Mutant("D3-c", "D3", "xml", "Nombre del esquema tributario en mayúscula inicial",
           _with(f"{TC}/TaxSchemeName", lambda e: e["table"].update({"IVA19": "Iva", "IVA5": "Iva"}))),
    Mutant("D3-d", "D3", "xml", "Moneda en minúsculas",
           _with("Invoice/DocumentCurrencyCode", lambda e: e.update({"transform": "literal", "value": "cop"}))),
    # D4 — campo ausente en el canónico
    Mutant("D4-a", "D4", "xml", "Correo del adquirente sin mapear (el canónico antiguo no lo traía)",
           _drop(f"{CUST}/ElectronicMail")),
    Mutant("D4-b", "D4", "xml", "Correo del adquirente mapeado a un literal vacío",
           _with(f"{CUST}/ElectronicMail", lambda e: e.update({"transform": "literal", "value": ""}))),
    Mutant("D4-c", "D4", "xml", "Fecha de emisión sin mapear",
           _drop("Invoice/IssueDate")),
    # D5 — campo obsoleto por versión
    Mutant("D5-a", "D5", "xml", "Se envía LegacyOrderReference, retirado en v2",
           _add({"target": "Invoice/LegacyOrderReference", "source": "documentNumber", "after": "Invoice/ID"})),
    Mutant("D5-b", "D5", "xml", "Se envía TaxExchangeRateLegacy, retirado en v2",
           _add({"target": "Invoice/TaxExchangeRateLegacy", "transform": "literal", "value": "1.00",
                 "after": "Invoice/DocumentCurrencyCode"})),
    # D6 — carácter reservado
    Mutant("D6-a", "D6", "seg", "Descripción sin sanear el separador reservado", _beta("sanitizeDescription", False)),
    Mutant("D6-b", "D6", "seg", "Nombre del adquirente sin sanear el separador reservado", _beta("sanitizeCustomerName", False)),
]
