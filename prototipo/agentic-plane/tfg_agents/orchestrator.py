"""Orquestador del ciclo gobernado: proponer → verificar con el kit → registrar → revisión humana.

En la arquitectura objetivo este flujo es un grafo de LangGraph; aquí se implementa como máquina de
estados explícita para que la PoC no dependa de servicios externos. Las transiciones son las mismas.
"""
from __future__ import annotations

import argparse
import json
import tempfile
from collections import Counter
from pathlib import Path

from . import POC_HOME, proposals
from .canonical import fixtures, generate
from .kit_client import verify_batch
from .mapping import load_mapping, render_alfa
from .mapping_agent import Backend, HeuristicBackend, default_backend, field_accuracy

GOLDEN = POC_HOME / "agentic-plane" / "golden" / "mapping-alfa-v2.json"


def verify_proposal(mapping: dict, sample: int = 50) -> dict:
    """Verifica una propuesta: kit en ejecución sobre una muestra y gate de CI sobre los fixtures."""
    work = Path(tempfile.mkdtemp(prefix="tfg-prop-"))
    golden = load_mapping(GOLDEN)
    rows = []
    for i, inv in enumerate(generate(sample, seed=99)):
        p = work / f"s-{i}.xml"
        p.write_text(render_alfa(inv, mapping), encoding="utf-8")
        rows.append((f"run:{i}", "xml", "RUNTIME", p, None))
    for i, inv in enumerate(fixtures()):
        p, e = work / f"f-{i}.xml", work / f"e-{i}.xml"
        p.write_text(render_alfa(inv, mapping), encoding="utf-8")
        e.write_text(render_alfa(inv, golden), encoding="utf-8")
        rows.append((f"ci:{i}", "xml", "CI", p, e))
    reports = verify_batch(rows, work)
    failed = [r for r in reports.values() if not r["passed"]]
    rules = Counter(f["ruleId"] for r in failed for f in r["findings"])
    types = Counter(f["type"] for r in failed for f in r["findings"])
    examples = []
    for r in failed:
        for f in r["findings"]:
            if f["ruleId"] not in {x["ruleId"] for x in examples}:
                examples.append(f)
    return {"documents": len(reports), "failedDocuments": len(failed), "gatePassed": not failed,
            "findingsByRule": dict(rules), "findingsByType": dict(types), "examples": examples[:8]}


def run(backend: Backend | None = None) -> dict:
    backend = backend or default_backend()
    proposal = backend.propose()                               # 1. el agente propone
    verification = verify_proposal(proposal)                   # 2. el kit decide si puede revisarse
    accuracy = field_accuracy(proposal, load_mapping(GOLDEN))  # solo en evaluación: hay mapeo dorado
    verification["fieldAccuracy"] = accuracy
    pid = proposals.save(backend.name, "mapping", proposal, verification, verification["gatePassed"])  # 3. registro
    bundle = POC_HOME / "results" / "proposals" / pid           # 4. "pull request": nunca despliega
    bundle.mkdir(parents=True, exist_ok=True)
    (bundle / "mapping.json").write_text(json.dumps(proposal, ensure_ascii=False, indent=2), encoding="utf-8")
    (bundle / "verification.json").write_text(json.dumps(verification, ensure_ascii=False, indent=2), encoding="utf-8")
    return {"proposalId": pid, "status": proposals.get(pid)["status"], **verification}


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--backend", choices=["auto", "heuristic"], default="auto")
    a = ap.parse_args()
    res = run(HeuristicBackend() if a.backend == "heuristic" else None)
    print(json.dumps(res, ensure_ascii=False, indent=2))
