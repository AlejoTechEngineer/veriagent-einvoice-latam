"""Banco ampliado de mutantes (Fase 6b), generado con los MISMOS operadores de ``mutations.py``.

El banco original de 22 mutantes (el del documento) no cambia. El ampliado recorre el espacio de parámetros de
cada operador de D1 a D6, excluye los mutantes equivalentes con un criterio escrito y toma hasta 30 por tipo con
una semilla fija. Cada mutante declara su verdad de base: defectuoso y no equivalente.

Criterio de equivalencia (se excluyen y se cuentan):
- E1. El mapeo mutado es idéntico a la línea base (operación nula, por ejemplo poner en una tabla el valor que
  ya tenía).
- E2. El cambio solo afecta a claves de una tabla o a fuentes que el dominio del país de destino nunca produce
  (por ejemplo, el emisor en CO siempre es NIT: cambiar la fila PAS de la tabla del emisor no altera ningún
  documento posible).
- E3. El literal mutado coincide con el valor que la línea base ya produce en todo el dominio (por ejemplo, la
  moneda literal "COP" para comprobantes que solo son de CO).
Un mutante que el sistema no detecta NO se reclasifica como equivalente: se informa como no detectado.
"""
from __future__ import annotations

import hashlib
import json
import random
from dataclasses import dataclass
from typing import Any, Callable

from . import POC_HOME
from .mapping import load_mapping
from .mutations import CUST, TC, _add, _beta, _beta_table, _drop, _swap, _table, _with

SEED = 2026
PER_TYPE = 30
SUPP = "Invoice/AccountingSupplierParty/Party"

ALFA = load_mapping(POC_HOME / "agentic-plane/golden/mapping-alfa-v2.json")
BETA_PE = load_mapping(POC_HOME / "data/mappings/beta-PE-v1.json")

# Dominio canónico de cada destino (lo que el generador y los ejemplos validados pueden producir).
DOMAIN = {
    "CO": {"documentType": {"INVOICE", "CREDIT_NOTE", "DEBIT_NOTE"}, "supplier.idType": {"NIT"},
           "customer.idType": {"NIT", "CC", "CE", "PAS"}, "lines[].taxCode": {"IVA19", "IVA5", "EXENTO"}, "currency": {"COP"}},
    "PE": {"documentType": {"INVOICE", "CREDIT_NOTE", "DEBIT_NOTE"}, "lines[].taxCode": {"IGV18", "EXONERADO"}},
}


@dataclass(frozen=True)
class Candidate:
    type: str
    format: str              # xml: alfa/CO; seg: beta/PE
    operator: str
    params: dict
    description: str
    apply: Callable[[dict], dict]
    domain_keys: tuple[str, str] | None = None   # (fuente canónica, clave) afectada, para E2
    literal_equal_to: tuple[str, str] | None = None  # (fuente canónica, literal), para E3


def _entry(m: dict, target: str) -> dict:
    return next(e for e in m["entries"] if e["target"] == target)


def _d1() -> list[Candidate]:
    out = []
    alfa_tables = {
        "Invoice/InvoiceTypeCode": ("documentType", ["01", "91", "92"], ["07", "93", "00"]),
        f"{CUST}/CompanyID/@schemeName": ("customer.idType", ["A1", "A2", "A3", "A4"], ["A5", "13"]),
        f"{SUPP}/CompanyID/@schemeName": ("supplier.idType", ["A1", "A2", "A3", "A4"], ["A5", "31"]),
        f"{TC}/ID": ("lines[].taxCode", ["T01", "T02", "T03"], ["T04", "01"]),
    }
    for target, (source, valid, invalid) in alfa_tables.items():
        for key, current in _entry(ALFA, target)["table"].items():
            for value in [v for v in valid if v != current] + invalid:
                out.append(Candidate("D1", "xml", "_table", {"target": target, "key": key, "value": value},
                                     f"{target}: {key} codificado como {value}", _table(target, key, value),
                                     domain_keys=(source, key)))
    beta_tables = {"docTypeTable": ("documentType", ["01", "07", "08"], ["93", "03"]),
                   "taxTable": ("lines[].taxCode", ["1000", "9997"], ["2000", "9998"])}
    for table, (source, valid, invalid) in beta_tables.items():
        for key, current in BETA_PE[table].items():
            for value in [v for v in valid if v != current] + invalid:
                out.append(Candidate("D1", "seg", "_beta_table", {"table": table, "key": key, "value": value},
                                     f"Beta PE {table}: {key} codificado como {value}", _beta_table(table, key, value),
                                     domain_keys=(source, key)))
    return out


def _d2() -> list[Candidate]:
    out = []
    levels = {"línea": ["InvoiceLine/InvoicedQuantity", "InvoiceLine/PriceAmount", "InvoiceLine/LineExtensionAmount",
                        "InvoiceLine/TaxCategory/Percent"],
              "totales": ["Invoice/TaxTotal/TaxAmount", "Invoice/LegalMonetaryTotal/LineExtensionAmount",
                          "Invoice/LegalMonetaryTotal/PayableAmount"]}
    for level, targets in levels.items():
        for i, a in enumerate(targets):
            for b in targets[i + 1:]:
                out.append(Candidate("D2", "xml", "_swap", {"t1": a, "t2": b}, f"{a} y {b} intercambiados", _swap(a, b)))
        sources = {t: _entry(ALFA, t)["source"] for t in targets}
        for t in targets:
            for other in targets:
                if other != t:
                    src = sources[other]
                    out.append(Candidate("D2", "xml", "_with(source)", {"target": t, "source": src},
                                         f"{t} tomado de {src}", _with(t, lambda e, s=src: e.__setitem__("source", s))))
    return out


def _variants(text: str) -> list[str]:
    return [text.lower(), text.upper(), text.capitalize(), text + " ", " " + text, text.replace("-", " ")]


def _d3() -> list[Candidate]:
    out = []
    for v in dict.fromkeys(_variants("PerfilAlfa-2.1")):
        out.append(Candidate("D3", "xml", "_with(value)", {"target": "Invoice/ProfileID", "value": v},
                             f"Identificador de perfil '{v}'", _with("Invoice/ProfileID", lambda e, x=v: e.__setitem__("value", x))))
    target = f"{TC}/TaxSchemeName"
    for key, current in _entry(ALFA, target)["table"].items():
        for v in dict.fromkeys(_variants(current)):
            out.append(Candidate("D3", "xml", "_with(table)", {"target": target, "key": key, "value": v},
                                 f"Nombre del esquema tributario de {key} como '{v}'",
                                 _with(target, lambda e, k=key, x=v: e["table"].__setitem__(k, x)), domain_keys=("lines[].taxCode", key)))
    for v in dict.fromkeys(_variants("COP")):
        out.append(Candidate("D3", "xml", "_with(literal)", {"target": "Invoice/DocumentCurrencyCode", "value": v},
                             f"Moneda literal '{v}'",
                             _with("Invoice/DocumentCurrencyCode", lambda e, x=v: e.update({"transform": "literal", "value": x})),
                             literal_equal_to=("currency", v)))
    for key, current in _entry(ALFA, "Invoice/InvoiceTypeCode")["table"].items():
        for v in (" " + current, current + " ", current.lstrip("0") or "0"):
            out.append(Candidate("D3", "xml", "_table", {"target": "Invoice/InvoiceTypeCode", "key": key, "value": v},
                                 f"Tipo de documento {key} con formato '{v}'", _table("Invoice/InvoiceTypeCode", key, v),
                                 domain_keys=("documentType", key)))
    return out


def _d4() -> list[Candidate]:
    out = []
    for e in ALFA["entries"]:
        out.append(Candidate("D4", "xml", "_drop", {"target": e["target"]}, f"{e['target']} sin mapear", _drop(e["target"])))
    for e in ALFA["entries"]:
        if e.get("transform") not in ("literal", "decimal", "decimal2", "map") and e.get("source"):
            out.append(Candidate("D4", "xml", "_with(literal vacío)", {"target": e["target"]}, f"{e['target']} como literal vacío",
                                 _with(e["target"], lambda x: x.update({"transform": "literal", "value": ""}))))
    return out


def _d5() -> list[Candidate]:
    out = []
    retired = {"Invoice/LegacyOrderReference": "Invoice/ID", "Invoice/TaxExchangeRateLegacy": "Invoice/DocumentCurrencyCode"}
    values = [("source", "documentNumber"), ("source", "originalDocumentNumber"), ("source", "currency"),
              ("literal", "1.00"), ("literal", "REF-0001")]
    for target, after in retired.items():
        for kind, v in values:
            entry = {"target": target, "after": after, **({"source": v} if kind == "source" else {"transform": "literal", "value": v})}
            out.append(Candidate("D5", "xml", "_add", {"target": target, kind: v}, f"Se envía {target} ({kind} {v})", _add(entry)))
    return out


def _d6() -> list[Candidate]:
    return [Candidate("D6", "seg", "_beta", {"key": "sanitizeDescription", "value": False}, "Descripción sin sanear",
                      _beta("sanitizeDescription", False)),
            Candidate("D6", "seg", "_beta", {"key": "sanitizeCustomerName", "value": False}, "Nombre del adquirente sin sanear",
                      _beta("sanitizeCustomerName", False)),
            Candidate("D6", "seg", "_beta(ambos)", {"keys": ["sanitizeDescription", "sanitizeCustomerName"], "value": False},
                      "Descripción y nombre sin sanear",
                      lambda m: _beta("sanitizeCustomerName", False)(_beta("sanitizeDescription", False)(m)))]


def _canon(m: dict) -> str:
    return json.dumps(m, sort_keys=True, ensure_ascii=False)


def equivalence(c: Candidate) -> str | None:
    base = ALFA if c.format == "xml" else BETA_PE
    country = "CO" if c.format == "xml" else "PE"
    if _canon(c.apply(base)) == _canon(base):
        return "E1"
    if c.domain_keys:
        source, key = c.domain_keys
        dom = DOMAIN[country].get(source)
        if dom is not None and key not in dom:
            return "E2"
    if c.literal_equal_to:
        source, literal = c.literal_equal_to
        if DOMAIN[country].get(source) == {literal}:
            return "E3"
    return None


def build(seed: int = SEED, per_type: int = PER_TYPE) -> dict:
    rng = random.Random(seed)
    generators = {"D1": _d1, "D2": _d2, "D3": _d3, "D4": _d4, "D5": _d5, "D6": _d6}
    bank, summary = [], {}
    for t, gen in generators.items():
        cands = gen()
        excluded = {"E1": 0, "E2": 0, "E3": 0}
        valid = []
        for c in cands:
            e = equivalence(c)
            if e:
                excluded[e] += 1
            else:
                valid.append(c)
        chosen = valid if len(valid) <= per_type else rng.sample(valid, per_type)
        summary[t] = {"espacio": len(cands), "equivalentesExcluidos": excluded, "noEquivalentes": len(valid),
                      "seleccionados": len(chosen), "admiteTreinta": len(valid) >= per_type}
        for i, c in enumerate(chosen, start=1):
            bank.append({"id": f"{t}-x{i:02d}", "type": t, "format": c.format, "operator": c.operator, "params": c.params,
                         "description": c.description, "groundTruth": "defectuoso", "equivalent": False,
                         "_apply": c.apply, "_candidate": c})
    digest = hashlib.sha256("\n".join(_canon({k: v for k, v in m.items() if not k.startswith("_")}) for m in bank).encode("utf-8")).hexdigest()
    return {"seed": seed, "perType": per_type, "summary": summary, "mutants": bank, "sha256": digest}


def equivalence_of(m: dict) -> str | None:
    """Criterio de equivalencia aplicado a un mutante ya seleccionado (para pruebas y auditoría)."""
    return equivalence(m["_candidate"])


def mapping_for(m: dict) -> tuple[str, str, dict]:
    if m["format"] == "xml":
        return "alfa", "CO", m["_apply"](ALFA)
    return "beta", "PE", m["_apply"](BETA_PE)
