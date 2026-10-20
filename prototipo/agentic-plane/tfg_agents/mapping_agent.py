"""Agente de mapeo: lee la especificación del proveedor y propone la especificación de mapeo.

Dos motores intercambiables detrás del mismo contrato:
- HeuristicBackend: similitud léxica y un glosario mínimo. Determinista; sirve de línea base.
- AnthropicBackend: un LLM con salida JSON estructurada. Requiere ANTHROPIC_API_KEY.
En ambos casos la salida es solo una PROPUESTA: el orquestador la verifica con el kit y la deja
pendiente de revisión humana. El agente nunca despliega.
"""
from __future__ import annotations

import json
import os
import re
from typing import Any, Protocol

from .canonical import FIELD_DESCRIPTIONS
from .spec_reader import FieldSpec, fields, literal_for, normalize, sections, sha256

# Conocimiento de dominio mínimo que un LLM aporta de forma nativa y la heurística necesita explícito.
GLOSSARY = {
    "INVOICE": "factura venta", "CREDIT_NOTE": "nota credito", "DEBIT_NOTE": "nota debito",
    "NIT": "identificacion tributaria empresa", "CC": "documento nacional persona",
    "CE": "documento extranjero", "PAS": "pasaporte",
    "IVA19": "iva general 19", "IVA5": "iva reducido 5", "EXENTO": "exento 0",
}
INTERNAL_CODES = {
    "documentType": ["INVOICE", "CREDIT_NOTE", "DEBIT_NOTE"],
    "supplier.idType": ["NIT", "CC", "CE", "PAS"], "customer.idType": ["NIT", "CC", "CE", "PAS"],
    "lines[].taxCode": ["IVA19", "IVA5", "EXENTO"],
}


class Backend(Protocol):
    name: str
    def propose(self) -> dict[str, Any]: ...


def _catalog_rows(name: str) -> list[list[str]]:
    for s in sections():
        if name in s.title:
            rows = []
            for line in s.text.splitlines():
                cells = [c.strip() for c in line.strip().strip("|").split("|")]
                if len(cells) >= 2 and not cells[0].startswith("---") and cells[0] not in ("Código",):
                    rows.append(cells)
            return rows
    return []


def _best_code(internal: str, rows: list[list[str]]) -> str:
    q = set(normalize(GLOSSARY.get(internal, internal)))
    return max(rows, key=lambda r: len(q & set(normalize(" ".join(r[1:])))))[0]


class HeuristicBackend:
    name = "heuristic-v1"

    def _score(self, f: FieldSpec, source: str) -> float:
        a, b = set(normalize(f.description)), set(normalize(FIELD_DESCRIPTIONS[source]))
        s = len(a & b) / len(a | b) if a | b else 0.0
        line_t, line_s = f.target.startswith("InvoiceLine/"), source.startswith("lines[]")
        if line_t != line_s:
            return -1.0
        if "Supplier" in f.target and not source.startswith("supplier"):
            s -= 0.3
        if "Customer" in f.target and not source.startswith("customer"):
            s -= 0.3
        return s

    def propose(self) -> dict[str, Any]:
        entries = []
        for f in fields():
            if f.kind == "literal" and not f.catalog:
                entries.append({"target": f.target, "transform": "literal", "value": literal_for(f.target) or "",
                                "evidence": {"section": "§2.1", "score": 1.0}})
                continue
            source, score = max(((s, self._score(f, s)) for s in FIELD_DESCRIPTIONS), key=lambda x: x[1])
            e: dict[str, Any] = {"target": f.target, "source": source,
                                 "evidence": {"section": f.section, "score": round(score, 3)}}
            if f.catalog:
                rows = _catalog_rows(f.catalog)
                codes = INTERNAL_CODES.get(source, [])
                if f.kind == "literal":  # nombre del esquema: columna 4 del catálogo de tributos
                    table = {c: next(r[3] for r in rows if r[0] == _best_code(c, rows)) for c in codes}
                elif f.kind == "decimal":
                    e["transform"] = "decimal2"
                    entries.append(e)
                    continue
                else:
                    table = {c: _best_code(c, rows) for c in codes}
                e.update({"transform": "map", "table": table})
            elif f.kind == "importe":
                e["transform"] = "decimal2"
            elif f.kind == "decimal":
                e["transform"] = "decimal"
            entries.append(e)
        return {"profile": "alfa-2.1", "version": "0.1.0-propuesta", "generatedBy": self.name,
                "specSha256": sha256(), "entries": entries}


class AnthropicBackend:
    """Motor LLM. El prompt incluye solo la especificación y el esquema canónico: nunca datos reales."""
    name = "anthropic-llm"

    def __init__(self, client: Any | None = None, model: str | None = None):
        if client is None:
            import anthropic  # importación diferida: la PoC funciona sin clave
            from . import egress
            client = anthropic.Anthropic(http_client=egress.sdk_client(timeout=120.0))
        self.client = client
        self.model = model or os.environ.get("TFG_LLM_MODEL", "claude-sonnet-5-5")

    def _prompt(self) -> tuple[str, str]:
        system = ("Eres un agente de mapeo de facturación electrónica. Propones una especificación de mapeo "
                  "entre un modelo canónico y un perfil nacional. Responde SOLO con JSON válido, sin texto "
                  "adicional: {\"entries\": [{\"target\", \"source\" | \"value\", \"transform\": "
                  "identity|literal|map|decimal|decimal2, \"table\"?, \"evidence\": {\"section\"}}]}. "
                  "Cada entrada debe citar la sección de la especificación que la justifica.")
        spec = "\n\n".join(f"{s.id} {s.title}\n{s.text}" for s in sections() if not s.id.startswith("§B"))
        canon = json.dumps(FIELD_DESCRIPTIONS, ensure_ascii=False, indent=1)
        codes = json.dumps(INTERNAL_CODES, ensure_ascii=False)
        user = (f"ESPECIFICACIÓN (sha256 {sha256()}):\n{spec}\n\nMODELO CANÓNICO (campo: descripción):\n{canon}\n\n"
                f"CÓDIGOS INTERNOS DEL CANÓNICO: {codes}\n\nPropón una entrada por cada fila de la tabla §6.")
        return system, user

    def propose(self) -> dict[str, Any]:
        system, user = self._prompt()
        msg = self.client.messages.create(model=self.model, max_tokens=8000, system=system,
                                          messages=[{"role": "user", "content": user}])
        text = "".join(getattr(b, "text", "") for b in msg.content)
        text = re.sub(r"^```(?:json)?|```$", "", text.strip(), flags=re.M).strip()
        data = json.loads(text)
        valid_targets = {f.target for f in fields()}
        entries = [e for e in data["entries"] if e.get("target") in valid_targets
                   and (e.get("transform") == "literal" or e.get("source") in FIELD_DESCRIPTIONS)]
        return {"profile": "alfa-2.1", "version": "0.1.0-propuesta", "generatedBy": f"{self.name}:{self.model}",
                "specSha256": sha256(), "entries": entries}


def default_backend() -> Backend:
    return AnthropicBackend() if os.environ.get("ANTHROPIC_API_KEY") else HeuristicBackend()


def field_accuracy(proposal: dict, golden: dict) -> dict:
    """Exactitud por campo frente al mapeo dorado: misma fuente, transformación, tabla y literal."""
    keys = ("source", "transform", "table", "value")
    g = {e["target"]: {k: e.get(k) for k in keys if e.get(k) is not None} for e in golden["entries"]}
    norm = lambda e: {k: e.get(k) for k in keys if e.get(k) is not None and not (k == "transform" and e.get(k) == "identity")}
    p = {e["target"]: norm(e) for e in proposal["entries"]}
    gn = {t: {k: v for k, v in d.items() if not (k == "transform" and v == "identity")} for t, d in g.items()}
    correct = [t for t in gn if p.get(t) == gn[t]]
    wrong = sorted(t for t in gn if t not in correct)
    return {"fields": len(gn), "correct": len(correct), "accuracy": round(len(correct) / len(gn), 4), "wrongTargets": wrong}
