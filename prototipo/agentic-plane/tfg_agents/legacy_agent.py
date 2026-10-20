"""Agente de extracción de legado (tabla 12): extrae reglas de código heredado con evidencia de archivo y línea.

Lee código heredado (aquí, un paquete PL/SQL sintético en ``data/legacy``), reconoce tablas de códigos y
saneamientos de texto, y propone un mapeo ``beta-pe-segmentos`` en el que cada valor cita su archivo y línea.
Como el resto de agentes, solo propone: la propuesta pasa por el kit y por una persona revisora.
"""
from __future__ import annotations

import hashlib
import re
from dataclasses import asdict, dataclass
from pathlib import Path

from . import POC_HOME

LEGACY_DIR = POC_HOME / "data" / "legacy"

# Qué significa cada función o campo del sistema heredado en el mapeo del proveedor.
FUNCTION_TO_TABLE = {"tipo_doc": "docTypeTable", "tributo": "taxTable"}
FIELD_TO_FLAG = {"descripcion": "sanitizeDescription", "razon_social_cliente": "sanitizeCustomerName"}

RX_FUNCTION = re.compile(r"^\s*FUNCTION\s+(\w+)\s*\(", re.I)
RX_END = re.compile(r"^\s*END\s+(\w+)\s*;", re.I)
RX_CASE = re.compile(r"(?:IF|ELSIF)\s+p_\w+\s*=\s*'([A-Z0-9_]+)'\s+THEN\s+RETURN\s+'([A-Z0-9]+)'", re.I)
RX_REPLACE = re.compile(r"REPLACE\(\s*\w+\s*,\s*'(.)'\s*,\s*'(.)'\s*\)", re.I)
RX_CALL = re.compile(r"(\w+)\(\s*[\w.()]*?\.?(\w+)\s*\)")


@dataclass(frozen=True)
class Rule:
    kind: str          # code-map | sanitize
    name: str          # función o campo de origen
    source: str | None
    target: str | None
    file: str
    line: int


def extract(path: Path) -> list[Rule]:
    rel = str(path.relative_to(POC_HOME)).replace("\\", "/")
    rules, current, sanitizers = [], None, {}
    lines = path.read_text(encoding="utf-8").splitlines()
    for n, line in enumerate(lines, start=1):
        if m := RX_FUNCTION.match(line):
            current = m.group(1).lower()
        elif (m := RX_END.match(line)) and current and m.group(1).lower() == current:
            current = None
        if current and (m := RX_CASE.search(line)):
            rules.append(Rule("code-map", current, m.group(1), m.group(2), rel, n))
        if current and (m := RX_REPLACE.search(line)):
            sanitizers[current] = (m.group(1), m.group(2), n)
    for n, line in enumerate(lines, start=1):
        for fn, (old, new, _) in sanitizers.items():
            for m in re.finditer(rf"{fn}\(\s*([\w.()]+)\s*\)", line, re.I):
                field = m.group(1).split(".")[-1].lower()
                rules.append(Rule("sanitize", field, old, new, rel, n))
    return rules


def propose(path: Path | None = None) -> dict:
    """Mapeo ``beta-pe-segmentos`` reconstruido desde el código heredado, con la evidencia de cada valor."""
    path = path or LEGACY_DIR / "pkg_fact_beta_pe.sql"
    rules = extract(path)
    mapping: dict = {"profile": "beta-pe-segmentos", "version": "0.1.0-legado", "generatedBy": "legacy-extraction-v1",
                     "legacySha256": hashlib.sha256(path.read_bytes()).hexdigest(), "evidence": []}
    for r in rules:
        if r.kind == "code-map" and r.name in FUNCTION_TO_TABLE:
            mapping.setdefault(FUNCTION_TO_TABLE[r.name], {})[r.source] = r.target
        elif r.kind == "sanitize" and r.name in FIELD_TO_FLAG and r.source == "|":
            mapping[FIELD_TO_FLAG[r.name]] = True
        else:
            continue
        mapping["evidence"].append(asdict(r))
    return mapping
