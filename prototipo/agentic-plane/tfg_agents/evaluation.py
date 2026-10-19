"""Arnés de evaluación: banco de defectos por inyección contra tres mecanismos de verificación.

Mecanismos: SCHEMA_ONLY (solo estructura), RUNTIME (kit en ejecución) y CI (kit + fixtures validados).
Métricas: mutantes detectados por tipo, tasa de detección por documento afectado, exactitud de la
clasificación y falsos positivos sobre salidas correctas.
"""
from __future__ import annotations

import argparse
import json
import tempfile
import time
from collections import defaultdict
from pathlib import Path

from . import POC_HOME
from .canonical import fixtures, generate
from .kit_client import verify_batch
from .mapping import load_mapping, render_alfa, render_beta
from .mutations import MUTANTS

GOLDEN = POC_HOME / "agentic-plane" / "golden"


def _render(fmt: str, inv, mapping) -> str:
    return render_alfa(inv, mapping) if fmt == "xml" else render_beta(inv, mapping)


def run(n_docs: int = 300, seed: int = 2026, out_dir: Path | None = None) -> dict:
    t0 = time.perf_counter()
    batch, fx = generate(n_docs, seed), fixtures()
    golden = {"xml": load_mapping(GOLDEN / "mapping-alfa-v2.json"), "seg": load_mapping(GOLDEN / "mapping-beta.json")}
    work = Path(tempfile.mkdtemp(prefix="tfg-eval-"))
    rows, meta = [], {}

    def write(name: str, content: str) -> Path:
        p = work / name
        p.write_text(content, encoding="utf-8")
        return p

    gold_out = {fmt: [_render(fmt, inv, golden[fmt]) for inv in batch] for fmt in golden}
    gold_fx = {fmt: [write(f"gfx-{fmt}-{i}.{fmt}", _render(fmt, inv, golden[fmt])) for i, inv in enumerate(fx)] for fmt in golden}

    # Línea de control: salidas correctas para medir falsos positivos.
    for fmt in golden:
        for i, content in enumerate(gold_out[fmt]):
            p = write(f"g-{fmt}-{i}.{fmt}", content)
            for mode in ("SCHEMA_ONLY", "RUNTIME"):
                rows.append((f"gold:{fmt}:{mode}:{i}", fmt, mode, p, None))
        for i, p in enumerate(gold_fx[fmt]):
            rows.append((f"gold:{fmt}:CI:{i}", fmt, "CI", p, p))

    for m in MUTANTS:
        mapping = m.apply(golden[m.format])
        affected = []
        for i, inv in enumerate(batch):
            content = _render(m.format, inv, mapping)
            if content != gold_out[m.format][i]:
                affected.append(i)
            p = write(f"m-{m.id}-{i}.{m.format}", content)
            for mode in ("SCHEMA_ONLY", "RUNTIME"):
                rows.append((f"{m.id}:{mode}:{i}", m.format, mode, p, None))
        for i, inv in enumerate(fx):
            p = write(f"mfx-{m.id}-{i}.{m.format}", _render(m.format, inv, mapping))
            rows.append((f"{m.id}:CI:{i}", m.format, "CI", p, gold_fx[m.format][i]))
        meta[m.id] = affected

    t_kit = time.perf_counter()
    reports = verify_batch(rows, work)
    kit_seconds = time.perf_counter() - t_kit

    def failed(rid: str) -> bool:
        return not reports[rid]["passed"]

    fp = {mode: sum(1 for k in reports if k.startswith("gold:") and f":{mode}:" in k and failed(k))
          for mode in ("SCHEMA_ONLY", "RUNTIME", "CI")}
    per_mutant = []
    for m in MUTANTS:
        aff = meta[m.id]
        sch = [i for i in aff if failed(f"{m.id}:SCHEMA_ONLY:{i}")]
        run_ = [i for i in aff if failed(f"{m.id}:RUNTIME:{i}")]
        ci_fx = [i for i in range(len(fx)) if failed(f"{m.id}:CI:{i}")]
        correct = [i for i in run_ if any(f["type"] == m.type for f in reports[f"{m.id}:RUNTIME:{i}"]["findings"])]
        per_mutant.append({
            "id": m.id, "type": m.type, "format": m.format, "description": m.description,
            "affectedDocs": len(aff),
            "killedSchema": bool(sch), "killedRuntime": bool(run_), "killedCI": bool(run_) or bool(ci_fx),
            "docDetectionSchema": round(len(sch) / len(aff), 4) if aff else None,
            "docDetectionRuntime": round(len(run_) / len(aff), 4) if aff else None,
            "classificationAccuracy": round(len(correct) / len(run_), 4) if run_ else None,
            "fixturesFailed": len(ci_fx),
            "exampleFinding": (reports[f"{m.id}:RUNTIME:{run_[0]}"]["findings"][0] if run_ else
                               (reports[f"{m.id}:CI:{ci_fx[0]}"]["findings"][0] if ci_fx else None)),
        })
    by_type = defaultdict(lambda: {"mutants": 0, "schema": 0, "runtime": 0, "ci": 0})
    for r in per_mutant:
        t = by_type[r["type"]]
        t["mutants"] += 1
        t["schema"] += r["killedSchema"]
        t["runtime"] += r["killedRuntime"]
        t["ci"] += r["killedCI"]
    total = {k: sum(v[k] for v in by_type.values()) for k in ("mutants", "schema", "runtime", "ci")}
    result = {
        "config": {"documents": n_docs, "fixtures": len(fx), "seed": seed, "mutants": len(MUTANTS)},
        "verifications": len(reports), "kitSeconds": round(kit_seconds, 2),
        "throughputDocsPerSecond": round(len(reports) / kit_seconds, 1),
        "falsePositives": fp, "goldenChecked": {"SCHEMA_ONLY": 2 * n_docs, "RUNTIME": 2 * n_docs, "CI": 2 * len(fx)},
        "byType": dict(sorted(by_type.items())), "total": total, "mutants": per_mutant,
        "elapsedSeconds": round(time.perf_counter() - t0, 2),
    }
    if out_dir:
        out_dir.mkdir(parents=True, exist_ok=True)
        (out_dir / "evaluation.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        (out_dir / "evaluation.md").write_text(to_markdown(result), encoding="utf-8")
    return result


def pct(a: int, b: int) -> str:
    return f"{100 * a / b:.0f} %" if b else "—"


def to_markdown(r: dict) -> str:
    lines = ["# Resultados del banco de defectos", "",
             f"{r['config']['mutants']} mutantes sobre {r['config']['documents']} comprobantes sintéticos y "
             f"{r['config']['fixtures']} fixtures; {r['verifications']} verificaciones en {r['kitSeconds']} s.", "",
             "| Tipo | Mutantes | Solo esquema | Kit en ejecución | Kit + CI |", "|---|---|---|---|---|"]
    for t, v in r["byType"].items():
        lines.append(f"| {t} | {v['mutants']} | {pct(v['schema'], v['mutants'])} | {pct(v['runtime'], v['mutants'])} | {pct(v['ci'], v['mutants'])} |")
    T = r["total"]
    lines.append(f"| **Total** | **{T['mutants']}** | **{pct(T['schema'], T['mutants'])}** | **{pct(T['runtime'], T['mutants'])}** | **{pct(T['ci'], T['mutants'])}** |")
    lines += ["", f"Falsos positivos sobre salidas correctas: {r['falsePositives']}", "",
              "| Mutante | Descripción | Docs afectados | Esquema | Ejecución | CI | Detección por doc. (ejecución) |",
              "|---|---|---|---|---|---|---|"]
    yn = lambda b: "sí" if b else "no"
    for m in r["mutants"]:
        dd = "—" if m["docDetectionRuntime"] is None else f"{100 * m['docDetectionRuntime']:.0f} %"
        lines.append(f"| {m['id']} | {m['description']} | {m['affectedDocs']} | {yn(m['killedSchema'])} | "
                     f"{yn(m['killedRuntime'])} | {yn(m['killedCI'])} | {dd} |")
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--docs", type=int, default=300)
    ap.add_argument("--seed", type=int, default=2026)
    ap.add_argument("--out", type=Path, default=POC_HOME / "results")
    a = ap.parse_args()
    res = run(a.docs, a.seed, a.out)
    print(to_markdown(res))
