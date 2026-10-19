"""Siembra la base de propuestas para la demostración de la consola de revisión."""
from __future__ import annotations

import json

from . import proposals
from .mapping_agent import HeuristicBackend, field_accuracy
from .mapping import load_mapping
from .orchestrator import GOLDEN, run, verify_proposal

if __name__ == "__main__":
    first = run(HeuristicBackend())                        # propuesta del agente: el kit la bloquea
    corrected = HeuristicBackend().propose()               # retrabajo tras el hallazgo R-D3-02 (§5.1)
    for e in corrected["entries"]:
        if e["target"] == "InvoiceLine/TaxCategory/TaxSchemeName":
            e.update({"source": "lines[].taxCode", "table": {"IVA19": "IVA", "IVA5": "IVA", "EXENTO": "EXENTO"},
                      "evidence": {"section": "§5.1", "score": 1.0, "nota": "corregido tras hallazgo R-D3-02"}})
    corrected["version"] = "0.1.1-propuesta"
    v = verify_proposal(corrected)
    v["fieldAccuracy"] = field_accuracy(corrected, load_mapping(GOLDEN))
    pid = proposals.save("heuristic-v1+retrabajo", "mapping", corrected, v, v["gatePassed"])
    print(json.dumps({"bloqueada": first["proposalId"], "pendiente": pid, "gate": v["gatePassed"]}, ensure_ascii=False))
