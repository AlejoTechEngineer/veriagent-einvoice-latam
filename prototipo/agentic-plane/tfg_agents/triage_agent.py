"""Agente de triaje de la DLQ (tabla 12): clasifica los fallos según la taxonomía y propone la corrección.

Lee las entradas abiertas de la DLQ con su propio token (rol AGENT) y registra su clasificación y su
recomendación. Nunca reprocesa ni descarta: eso lo decide una persona con rol OPERATOR (la plataforma devuelve
403 al agente). La clasificación es determinista; los tipos D1 a D6 salen de los hallazgos del kit.
"""
from __future__ import annotations

import json
import re

from .platform_client import PlatformClient

RX_RULE = re.compile(r"\b(R-(D[1-6]|E0)-\d{2}|G-(D[1-6])-\d{2}|VAL-[\w-]+|CAT-\d{2})\b")


def classify(entry: dict) -> dict:
    """Clasificación y recomendación para una entrada de la DLQ."""
    stage = (entry.get("stage") or "").upper()
    category = (entry.get("category") or "").upper()
    error = entry.get("lastError") or ""
    findings = []
    try:
        findings = json.loads(entry.get("findings") or "[]") or []
    except (TypeError, ValueError):
        pass
    rules = sorted({f.get("ruleId") for f in findings if f.get("ruleId")} | {m.group(1) for m in RX_RULE.finditer(error)})
    types = sorted({f.get("type") for f in findings if f.get("type")}
                   | {t for m in RX_RULE.finditer(error) for t in (m.group(2), m.group(3)) if t})
    defect = types[0] if types else None
    evidence = [f"etapa={stage}", f"categoria={category}"] + [f"regla={r}" for r in rules]
    if error:
        evidence.append("error=" + error[:300])

    if stage == "INGEST":
        c, r, why = "ENTRADA_INVALIDA", "CORREGIR_EN_ORIGEN", "El comprobante no cumple el canónico; se corrige en el sistema de origen y se reenvía."
    elif stage == "ROUTING":
        c, r, why = "SIN_RUTA", "CONFIGURAR_ENRUTAMIENTO", "No hay regla de enrutamiento para el país y el tipo de documento."
    elif "CAT-01" in error or "Catálogo del proveedor" in error:
        c, r, why = ("RECHAZO_CATALOGO_PROVEEDOR", "REPROCESAR_TRAS_CORRECCION",
                     "El proveedor rechazó por su catálogo. Cuando el proveedor o el mapeo se corrijan, una persona operadora puede reprocesar.")
    elif "mapeo vigente" in error.lower() or "sin mapeo" in error.lower():
        c, r, why = "SIN_MAPEO_VIGENTE", "ACTIVAR_MAPEO_Y_REPROCESAR", "No hay mapeo aprobado y activo para el proveedor y el país."
    elif category == "RECOVERABLE":
        c, r, why = ("PROVEEDOR_NO_DISPONIBLE", "REPROCESAR_CUANDO_SE_RECUPERE",
                     "Fallo transitorio del proveedor (no disponible, lento o sin respuesta); el comprobante es válido.")
    elif defect:
        c, r, why = (f"DEFECTO_{defect}", "CORREGIR_EN_ORIGEN" if defect in ("D1", "D4") else "REVISAR_MAPEO",
                     "El documento tiene un defecto detectado por las reglas del kit o del proveedor.")
    else:
        c, r, why = "NO_CLASIFICADO", "REVISION_MANUAL", "No coincide con ninguna categoría de la taxonomía."
    return {"classification": c, "defectType": defect, "recommendation": r, "rationale": why, "evidence": evidence}


def run(client: PlatformClient | None = None, limit: int = 200) -> list[dict]:
    """Clasifica las entradas abiertas sin triaje y registra la recomendación en la plataforma."""
    client = client or PlatformClient()
    done = []
    for e in client.dlq_open(limit):
        if e.get("triage"):
            continue
        t = classify(e)
        client.save_triage(e["id"], t)
        done.append({"id": e["id"], "invoiceKey": e.get("invoiceKey"), **t})
    return done


if __name__ == "__main__":
    import sys
    sys.stdout.reconfigure(encoding="utf-8")
    print(json.dumps(run(), ensure_ascii=False, indent=2))
