from tfg_agents import mutation_bank as mb
from tfg_agents.mutations import MUTANTS
from tfg_agents.scenarios import wilson


def test_bank_is_reproducible_and_excludes_equivalents():
    a, b = mb.build(), mb.build()
    assert a["sha256"] == b["sha256"]
    assert all(m["groundTruth"] == "defectuoso" and not m["equivalent"] for m in a["mutants"])
    assert all(mb.equivalence_of(m) is None for m in a["mutants"])
    counts = {t: sum(1 for m in a["mutants"] if m["type"] == t) for t in ("D1", "D2", "D3", "D4", "D5", "D6")}
    assert counts == {t: v["seleccionados"] for t, v in a["summary"].items()}
    assert max(counts.values()) <= mb.PER_TYPE


def test_original_bank_is_untouched():
    assert len(MUTANTS) == 22


def test_wilson_interval():
    assert wilson(0, 10)[0] == 0.0 and wilson(10, 10)[1] == 1.0
    lo, hi = wilson(121, 129)
    assert 0.88 < lo < 0.89 and 0.96 < hi < 0.97
