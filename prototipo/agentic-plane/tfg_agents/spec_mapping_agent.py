"""Agente de mapeo para especificaciones nuevas (escenario S5: proveedor Gamma, país XX).

Lee la especificación a través del servidor mcp-spec-reader (tablas de campos y catálogos con su huella) y
propone un mapeo ``gamma-json-1``. Igual que el agente del Perfil Alfa, tiene dos motores:
- SpecHeuristicBackend: similitud léxica, restricciones de grupo (encabezado, partes, líneas, totales) y un
  glosario mínimo para los códigos. Determinista.
- SpecLlmBackend: un LLM con salida JSON; solo si existe ANTHROPIC_API_KEY.
La salida es solo una PROPUESTA: el orquestador la verifica con el kit y la deja para revisión humana.
"""
from __future__ import annotations

import json
import os
import re
from typing import Any

from .canonical import FIELD_DESCRIPTIONS
from .mcp_servers import call, spec_reader
from .spec_reader import normalize
from .specs import SPEC_BY_TARGET, spec_for

PROFILE_BY_TARGET = {("gamma", "XX"): "gamma-json-1"}

# Códigos internos del canónico por país (los del modelo canónico, no los del proveedor).
INTERNAL_CODES = {
    "XX": {"documentType": ["INVOICE", "CREDIT_NOTE", "DEBIT_NOTE"], "supplier.idType": ["TIN", "PAS"],
           "customer.idType": ["TIN", "PAS"], "lines[].taxCode": ["VAT12", "ZERO"]},
}
GLOSSARY = {
    "INVOICE": "factura", "CREDIT_NOTE": "nota credito", "DEBIT_NOTE": "nota debito",
    "TIN": "identificacion tributaria", "PAS": "pasaporte",
    "VAT12": "valor agregado general 12", "ZERO": "tasa cero",
}
FREE_TEXT = ("nombre", "razon", "descripcion")


def _group_ok(target: str, source: str) -> bool:
    """Restricción estructural: cada grupo de la especificación solo toma campos del grupo canónico equivalente."""
    if target.startswith("/lines[]"):
        return source.startswith("lines[]")
    if target.startswith("/totals"):
        return source.startswith("totals.")
    if "/seller/" in target:
        return source.startswith("supplier.")
    if "/buyer/" in target:
        return source.startswith("customer.")
    return "." not in source and not source.startswith("lines[]")


def _score(description: str, source: str) -> float:
    a, b = set(normalize(description)), set(normalize(FIELD_DESCRIPTIONS[source]))
    return len(a & b) / len(a | b) if a | b else 0.0


def _best_code(internal: str, rows: list[list[str]]) -> str:
    q = set(normalize(GLOSSARY.get(internal, internal)))
    return max(rows, key=lambda r: len(q & set(normalize(" ".join(r[1:])))))[0]


class SpecHeuristicBackend:
    name = "spec-heuristic-v1"

    def __init__(self, provider: str, country: str):
        self.provider, self.country = provider.lower(), country.upper()
        self.doc = spec_for(self.provider, self.country)

    def propose(self, feedback: list[dict] | None = None) -> dict[str, Any]:
        info = call(spec_reader, "campos_especificacion", {"especificacion": self.doc.path.name})
        reserved = self.doc.section_mentioning(r"caracter(es)? reservado")
        literal = self.doc.literal()
        codes = INTERNAL_CODES.get(self.country, {})
        entries = []
        for f in info["campos"]:
            target, desc, kind, cat, section = f["target"], f["description"], f["kind"], f["catalog"], f["section"]
            if kind == "literal":
                entries.append({"target": target, "transform": "literal", "value": literal or "",
                                "evidence": {"section": section, "spec": self.doc.path.name}})
                continue
            candidates = [s for s in FIELD_DESCRIPTIONS if _group_ok(target, s)]
            if not candidates:
                continue
            source, score = max(((s, _score(desc, s)) for s in candidates), key=lambda x: x[1])
            e: dict[str, Any] = {"target": target, "source": source,
                                 "evidence": {"section": section, "spec": self.doc.path.name, "score": round(score, 3)}}
            rows = self.doc.catalog(cat) if cat else []
            if kind == "código" and rows and source in codes:
                e.update({"transform": "map", "table": {c: _best_code(c, rows) for c in codes[source]}})
                e["evidence"]["catalog"] = cat
            elif kind == "entero":
                e["transform"] = "integer"
            elif kind == "importe" or (kind == "decimal" and cat):
                e["transform"] = "decimal2"
            elif kind == "decimal":
                e["transform"] = "decimal"
            elif kind == "texto" and reserved and any(w in normalize(desc) or w[:5] in normalize(desc) for w in FREE_TEXT):
                e["transform"] = "sanitize"
                e["evidence"]["rule"] = reserved.id
            entries.append(e)
        return {"profile": PROFILE_BY_TARGET[(self.provider, self.country)], "version": "0.1.0-propuesta",
                "generatedBy": self.name, "specSha256": info["sha256"], "spec": info["spec"], "entries": entries}


class SpecLlmBackend:
    """Motor LLM: el prompt incluye solo la especificación sintética y el canónico; nunca datos reales."""
    name = "anthropic-llm-spec"

    def __init__(self, provider: str, country: str, client: Any | None = None, model: str | None = None):
        if client is None:
            import anthropic
            client = anthropic.Anthropic()
        self.client = client
        self.model = model or os.environ.get("TFG_LLM_MODEL", "claude-sonnet-5-5")
        self.provider, self.country = provider.lower(), country.upper()
        self.doc = spec_for(self.provider, self.country)

    def propose(self, feedback: list[dict] | None = None) -> dict[str, Any]:
        text = self.doc.path.read_text(encoding="utf-8")
        system = ("Eres un agente de mapeo de facturación electrónica. Responde SOLO con JSON válido: "
                  "{\"entries\": [{\"target\", \"source\" | \"value\", \"transform\": identity|literal|map|decimal|"
                  "decimal2|sanitize|integer, \"table\"?, \"evidence\": {\"section\"}}]}. Cita la sección que justifica cada entrada.")
        user = (f"ESPECIFICACIÓN ({self.doc.path.name}, sha256 {self.doc.sha256}):\n{text}\n\n"
                f"MODELO CANÓNICO: {json.dumps(FIELD_DESCRIPTIONS, ensure_ascii=False)}\n"
                f"CÓDIGOS INTERNOS: {json.dumps(INTERNAL_CODES.get(self.country, {}), ensure_ascii=False)}\n"
                + (f"HALLAZGOS DEL KIT EN EL INTENTO ANTERIOR: {json.dumps(feedback, ensure_ascii=False)}\n" if feedback else "")
                + "Propón una entrada por cada fila de las tablas de campos.")
        msg = self.client.messages.create(model=self.model, max_tokens=8000, system=system,
                                          messages=[{"role": "user", "content": user}])
        raw = "".join(getattr(b, "text", "") for b in msg.content)
        data = json.loads(re.sub(r"^```(?:json)?|```$", "", raw.strip(), flags=re.M).strip())
        targets = {r.target for r in self.doc.field_rows()}
        entries = [e for e in data.get("entries", []) if e.get("target") in targets
                   and (e.get("transform") == "literal" or e.get("source") in FIELD_DESCRIPTIONS)]
        return {"profile": PROFILE_BY_TARGET[(self.provider, self.country)], "version": "0.1.0-propuesta",
                "generatedBy": f"{self.name}:{self.model}", "specSha256": self.doc.sha256, "spec": self.doc.path.name,
                "entries": entries}


def backend_for(provider: str, country: str):
    if (provider.lower(), country.upper()) not in PROFILE_BY_TARGET:
        raise KeyError(f"No hay agente de mapeo por especificación para {provider}/{country}")
    if os.environ.get("ANTHROPIC_API_KEY"):
        return SpecLlmBackend(provider, country)
    return SpecHeuristicBackend(provider, country)


__all__ = ["SpecHeuristicBackend", "SpecLlmBackend", "backend_for", "SPEC_BY_TARGET"]
