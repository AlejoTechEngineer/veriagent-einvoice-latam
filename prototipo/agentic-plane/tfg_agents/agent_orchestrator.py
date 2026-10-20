"""agent-orchestrator (tabla 12): grafo de LangGraph del ciclo gobernado de un mapeo.

    proponer -> autocomprobar con el kit (mcp-validator) -> [reintentar con los hallazgos] ->
    abrir solicitud de cambio (mcp-repo, sin merge) -> registrar la candidata en el registro de mapeos ->
    registrar la propuesta (agent_proposal) -> FIN: pendiente de revisión humana.

El grafo termina antes de cualquier decisión: no tiene ningún nodo que apruebe, active o revierta, y el token
del agente no lo permitiría (403). Aprobar (con 409 si el kit bloqueó), activar y revertir son acciones de una
persona revisora sobre el registro de mapeos, con la identidad de su token (ADR-04 y ADR-05).
"""
from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
from typing import Any, TypedDict

from langgraph.graph import END, START, StateGraph

from . import proposals
from .mcp_servers import call, repo, validator
from .platform_client import PlatformClient

MAX_ATTEMPTS = 3


class CycleState(TypedDict, total=False):
    provider: str
    country: str
    agent: str
    attempt: int
    feedback: list[dict]
    mapping: dict
    check: dict
    branch: str
    pull_request: dict
    registry: dict
    proposal_id: str
    status: str


def make_backend(provider: str, country: str):
    """Motor del agente para cada destino: especificación nueva, Perfil Alfa o código heredado."""
    key = (provider.lower(), country.upper())
    if key == ("gamma", "XX"):
        from .spec_mapping_agent import backend_for
        return backend_for(*key)
    if key == ("alfa", "CO"):
        from .mapping_agent import default_backend
        inner = default_backend()

        class AlfaAdapter:
            name = inner.name
            def propose(self, feedback=None):
                return inner.propose()
        return AlfaAdapter()
    if key == ("beta", "PE"):
        from . import legacy_agent

        class LegacyAdapter:
            name = "legacy-extraction-v1"
            def propose(self, feedback=None):
                return legacy_agent.propose()
        return LegacyAdapter()
    raise KeyError(f"Sin agente para {provider}/{country}")


def build_graph(backend: Any, platform: PlatformClient | None = None):
    platform = platform or PlatformClient()

    def propose(s: CycleState) -> CycleState:
        return {"mapping": backend.propose(s.get("feedback")), "attempt": s.get("attempt", 0) + 1, "agent": backend.name}

    def self_check(s: CycleState) -> CycleState:
        r = call(validator, "verificar_mapeo", {"proveedor": s["provider"], "pais": s["country"], "mapeo": s["mapping"]})
        if "error" in r:
            raise RuntimeError(f"El validador no respondió: {r['error']}")
        return {"check": r, "feedback": r.get("examples", [])}

    def after_check(s: CycleState) -> str:
        # Solo un motor capaz de usar los hallazgos (LLM) reintenta; el heurístico es determinista.
        retry = not s["check"]["passed"] and s["attempt"] < MAX_ATTEMPTS and "llm" in backend.name
        return "propose" if retry else "open_pr"

    def open_pr(s: CycleState) -> CycleState:
        stamp = datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S")
        branch = call(repo, "crear_rama", {"nombre": f"mapeo-{s['provider']}-{s['country']}-{stamp}",
                                           "motivo": f"Propuesta del agente {s['agent']}"})["branch"]
        call(repo, "escribir_archivo", {"rama": branch, "ruta": f"mappings/{s['provider']}-{s['country']}.json",
                                        "contenido": json.dumps(s["mapping"], ensure_ascii=False, indent=2)})
        call(repo, "escribir_archivo", {"rama": branch, "ruta": "verificacion-kit.json",
                                        "contenido": json.dumps(s["check"], ensure_ascii=False, indent=2)})
        verdict = "pasa el kit" if s["check"]["passed"] else "BLOQUEADA por el kit"
        pr = call(repo, "abrir_solicitud", {"rama": branch, "titulo": f"Mapeo {s['provider']}/{s['country']} ({verdict})",
                                            "descripcion": f"Generado por {s['agent']}. Requiere revisión humana; no hay merge automático."})
        return {"branch": branch, "pull_request": pr}

    def submit(s: CycleState) -> CycleState:
        v = platform.propose_mapping(s["provider"], s["country"], s["mapping"], "agente", s["pull_request"]["id"])
        return {"registry": v}

    def record(s: CycleState) -> CycleState:
        v = s["registry"]
        verification = dict(v.get("verification") or {})
        verification["registryStatus"] = v["status"]
        pid = proposals.save(s["agent"], "mapping", s["mapping"], verification, bool(v["gatePassed"]),
                             registry=(v["provider"], v["country"], v["version"]), pull_request=s["pull_request"]["id"])
        return {"proposal_id": pid, "status": proposals.get(pid)["status"]}

    g = StateGraph(CycleState)
    for name, fn in [("propose", propose), ("self_check", self_check), ("open_pr", open_pr), ("submit", submit), ("record", record)]:
        g.add_node(name, fn)
    g.add_edge(START, "propose")
    g.add_edge("propose", "self_check")
    g.add_conditional_edges("self_check", after_check, {"propose": "propose", "open_pr": "open_pr"})
    g.add_edge("open_pr", "submit")
    g.add_edge("submit", "record")
    g.add_edge("record", END)
    return g.compile()


def run_cycle(provider: str, country: str, backend: Any | None = None, platform: PlatformClient | None = None) -> dict:
    backend = backend or make_backend(provider, country)
    final = build_graph(backend, platform).invoke({"provider": provider.lower(), "country": country.upper()})
    v = final["registry"]
    return {"proposalId": final["proposal_id"], "status": final["status"], "agent": final["agent"],
            "attempts": final["attempt"], "pullRequest": final["pull_request"]["id"], "branch": final["branch"],
            "registry": {"provider": v["provider"], "country": v["country"], "version": v["version"],
                         "status": v["status"], "gatePassed": v["gatePassed"]},
            "check": {k: final["check"].get(k) for k in ("passed", "runtimeDocuments", "runtimeFailed", "ciDocuments",
                                                          "ciFailed", "findingsByRule", "problem")}}


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description="Ciclo gobernado de un mapeo: el agente propone y el kit decide si puede revisarse.")
    ap.add_argument("--provider", required=True)
    ap.add_argument("--country", required=True)
    a = ap.parse_args()
    sys.stdout.reconfigure(encoding="utf-8")
    print(json.dumps(run_cycle(a.provider, a.country), ensure_ascii=False, indent=2))
