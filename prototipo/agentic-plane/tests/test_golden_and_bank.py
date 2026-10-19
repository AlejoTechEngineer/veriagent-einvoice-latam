"""Integración Python ↔ kit Java: el mapeo dorado no tiene falsos positivos y el banco detecta todo."""
import tempfile
from pathlib import Path

from tfg_agents.canonical import generate
from tfg_agents.evaluation import GOLDEN, run
from tfg_agents.kit_client import verify_batch
from tfg_agents.mapping import load_mapping, render_alfa, render_beta
from tfg_agents.mutations import MUTANTS


def test_golden_outputs_pass_runtime_kit():
    work = Path(tempfile.mkdtemp())
    alfa, beta = load_mapping(GOLDEN / "mapping-alfa-v2.json"), load_mapping(GOLDEN / "mapping-beta.json")
    rows = []
    for i, inv in enumerate(generate(40, seed=5)):
        (work / f"{i}.xml").write_text(render_alfa(inv, alfa), encoding="utf-8")
        (work / f"{i}.seg").write_text(render_beta(inv, beta), encoding="utf-8")
        rows += [(f"x{i}", "xml", "RUNTIME", work / f"{i}.xml", None), (f"s{i}", "seg", "RUNTIME", work / f"{i}.seg", None)]
    reports = verify_batch(rows, work)
    assert all(r["passed"] for r in reports.values()), [r for r in reports.values() if not r["passed"]][:1]


def test_every_mutant_type_is_represented():
    assert {m.type for m in MUTANTS} == {"D1", "D2", "D3", "D4", "D5", "D6"}
    assert len({m.id for m in MUTANTS}) == len(MUTANTS)


def test_defect_bank_kit_plus_ci_detects_all_without_false_positives():
    r = run(n_docs=60, seed=11)
    assert r["total"]["ci"] == r["total"]["mutants"], "el gate de CI debe detectar todos los mutantes"
    assert r["total"]["runtime"] > r["total"]["schema"], "las reglas deben aportar sobre el XSD"
    assert r["falsePositives"] == {"SCHEMA_ONLY": 0, "RUNTIME": 0, "CI": 0}
