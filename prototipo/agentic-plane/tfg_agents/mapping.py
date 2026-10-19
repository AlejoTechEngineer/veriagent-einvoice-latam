"""Especificación de mapeo (lo que el agente propone) y transformadores a los formatos destino."""
from __future__ import annotations

import copy
import json
from decimal import Decimal
from pathlib import Path
from typing import Any

from lxml import etree

from .canonical import InvoiceRequest

NS = "urn:tfg:alfa:invoice:2"

# Orden del documento según el XSD del perfil Alfa v2.1.
HEADER_LAYOUT = [
    "Invoice/ProfileID", "Invoice/ID", "Invoice/IssueDate", "Invoice/InvoiceTypeCode",
    "Invoice/DocumentCurrencyCode", "Invoice/BillingReference",
    "Invoice/AccountingSupplierParty/Party/PartyName", "Invoice/AccountingSupplierParty/Party/CompanyID",
    "Invoice/AccountingSupplierParty/Party/CompanyID/@schemeName",
    "Invoice/AccountingCustomerParty/Party/PartyName", "Invoice/AccountingCustomerParty/Party/CompanyID",
    "Invoice/AccountingCustomerParty/Party/CompanyID/@schemeName",
    "Invoice/AccountingCustomerParty/Party/ElectronicMail",
    "Invoice/TaxTotal/TaxAmount", "Invoice/LegalMonetaryTotal/LineExtensionAmount",
    "Invoice/LegalMonetaryTotal/PayableAmount",
]
LINE_LAYOUT = [
    "InvoiceLine/ID", "InvoiceLine/InvoicedQuantity", "InvoiceLine/LineExtensionAmount",
    "InvoiceLine/ItemDescription", "InvoiceLine/PriceAmount", "InvoiceLine/TaxCategory/ID",
    "InvoiceLine/TaxCategory/Percent", "InvoiceLine/TaxCategory/TaxSchemeName",
]


def load_mapping(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def _get(obj: Any, path: str) -> Any:
    cur = obj
    for part in path.split("."):
        if cur is None:
            return None
        cur = getattr(cur, part) if not isinstance(cur, dict) else cur.get(part)
    return cur


def _apply(entry: dict, value: Any) -> str | None:
    t = entry.get("transform", "identity")
    if t == "literal":
        return entry["value"]
    if value is None:
        return None
    if t == "map":
        return entry["table"].get(str(value), f"?{value}")
    if t == "decimal2":
        return f"{Decimal(value):.2f}"
    if t == "decimal":
        return format(Decimal(value).normalize(), "f")
    return str(value)


def _value(entry: dict, inv: InvoiceRequest, line: Any | None) -> str | None:
    src = entry.get("source")
    if entry.get("transform") == "literal":
        return entry["value"]
    if src is None:
        return None
    if src.startswith("lines[]."):
        return _apply(entry, _get(line, src[len("lines[]."):]))
    return _apply(entry, _get(inv, src))


def _set(root: etree._Element, rel: str, value: str) -> None:
    parts = rel.split("/")
    cur = root
    for i, p in enumerate(parts):
        if p.startswith("@"):
            cur.set(p[1:], value)
            return
        last = i == len(parts) - 1
        found = None if last else cur.find(f"{{{NS}}}{p}")
        if found is None:
            found = etree.SubElement(cur, f"{{{NS}}}{p}")
        cur = found
    cur.text = value


def render_alfa(inv: InvoiceRequest, mapping: dict[str, Any]) -> str:
    """Construye el XML del perfil Alfa siguiendo el orden del XSD y la especificación de mapeo."""
    by_target = {e["target"]: e for e in mapping["entries"]}
    extras = [e for e in mapping["entries"] if e["target"] not in HEADER_LAYOUT + LINE_LAYOUT]
    root = etree.Element(f"{{{NS}}}Invoice", nsmap={None: NS})
    for target in HEADER_LAYOUT:
        e = by_target.get(target)
        if e is not None:
            v = _value(e, inv, None)
            if v is not None:
                _set(root, target[len("Invoice/"):], v)
        for x in extras:
            if x.get("after") == target:
                v = _value(x, inv, None)
                if v is not None:
                    _set(root, x["target"][len("Invoice/"):], v)
    for line in inv.lines:
        il = etree.SubElement(root, f"{{{NS}}}InvoiceLine")
        for target in LINE_LAYOUT:
            e = by_target.get(target)
            if e is not None:
                v = _value(e, inv, line)
                if v is not None:
                    _set(il, target[len("InvoiceLine/"):], v)
    return etree.tostring(root, xml_declaration=True, encoding="UTF-8", pretty_print=True).decode("utf-8")


def render_beta(inv: InvoiceRequest, cfg: dict[str, Any]) -> str:
    """Formato por segmentos del proveedor Beta (§B). Sanea el separador reservado si así se configura."""
    def text(v: str, key: str) -> str:
        return v.replace("|", "/") if cfg.get(key, True) else v
    t = cfg["docTypeTable"][inv.documentType]
    out = ["|".join(["ENC", inv.documentNumber, inv.issueDate.isoformat(), t, inv.currency,
                     inv.supplier.taxId, inv.customer.taxId, text(inv.customer.name, "sanitizeCustomerName")])]
    for l in inv.lines:
        out.append("|".join(["DET", str(l.number), text(l.description, "sanitizeDescription"),
                             format(l.quantity.normalize(), "f"), f"{l.unitPrice:.2f}", f"{l.amount:.2f}",
                             cfg["taxTable"][l.taxCode]]))
    out.append("|".join(["TOT", f"{inv.totals.baseAmount:.2f}", f"{inv.totals.taxAmount:.2f}",
                         f"{inv.totals.payableAmount:.2f}"]))
    return "\n".join(out) + "\n"


def clone(mapping: dict[str, Any]) -> dict[str, Any]:
    return copy.deepcopy(mapping)
