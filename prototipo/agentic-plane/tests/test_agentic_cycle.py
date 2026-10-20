"""Fase 4: agentes, servidores MCP y ciclo gobernado, con la plataforma sustituida por un doble en memoria."""
import asyncio
import json
import secrets

import pytest
from fastapi.testclient import TestClient

from tfg_agents import POC_HOME, mcp_servers, proposals
from tfg_agents import review_api
from tfg_agents.agent_orchestrator import run_cycle
from tfg_agents.legacy_agent import propose as legacy_propose
from tfg_agents.mapping_agent import field_accuracy
from tfg_agents.platform_client import PlatformError
from tfg_agents.spec_mapping_agent import SpecHeuristicBackend
from tfg_agents.specs import spec
from tfg_agents.triage_agent import classify

REFERENCE = POC_HOME / "integration-plane/provider-common/src/test/resources/reference/gamma-XX-reference.json"


class FakePlatform:
    """Doble de la plataforma: decide el resultado del kit y registra cada llamada."""

    def __init__(self, passed=True):
        self.passed = passed
        self.calls = []

    def check_mapping(self, provider, country, mapping):
        self.calls.append(("check", provider, country))
        return {"passed": self.passed, "runtimeDocuments": 50, "runtimeFailed": 0 if self.passed else 50,
                "ciDocuments": 12, "ciFailed": 0, "findingsByRule": {} if self.passed else {"G-D1-01": 50},
                "examples": [] if self.passed else [{"ruleId": "G-D1-01"}], "problem": None}

    def propose_mapping(self, provider, country, mapping, source, ref):
        self.calls.append(("propose", provider, country, ref))
        return {"provider": provider, "country": country, "version": 2, "gatePassed": self.passed,
                "status": "CANDIDATE" if self.passed else "BLOCKED", "verification": self.check_mapping(provider, country, mapping)}


@pytest.fixture()
def sandbox(tmp_path, monkeypatch):
    monkeypatch.setenv("VERIAGENT_SANDBOX_REPO", str(tmp_path / "sandbox"))
    return tmp_path / "sandbox"


@pytest.fixture()
def fake_platform(monkeypatch):
    fake = FakePlatform()
    monkeypatch.setattr(mcp_servers, "_platform", lambda: fake)
    return fake


def test_spec_agent_proposes_the_gamma_mapping_from_the_spec():
    p = SpecHeuristicBackend("gamma", "XX").propose()
    acc = field_accuracy(p, json.loads(REFERENCE.read_text(encoding="utf-8")))
    assert acc["accuracy"] == 1.0, acc["wrongTargets"]
    assert p["specSha256"] == spec("proveedor-gamma-v1").sha256
    assert all("section" in e["evidence"] for e in p["entries"])


def test_legacy_agent_recovers_rules_with_file_and_line():
    p = legacy_propose()
    baseline = json.loads((POC_HOME / "data/mappings/beta-PE-v1.json").read_text(encoding="utf-8"))
    for k in ("docTypeTable", "taxTable", "sanitizeDescription", "sanitizeCustomerName"):
        assert p[k] == baseline[k], k
    assert all(e["file"] == "data/legacy/pkg_fact_beta_pe.sql" and e["line"] > 0 for e in p["evidence"])
    assert {"line": 8, "source": "INVOICE", "target": "01"}.items() <= p["evidence"][0].items()


@pytest.mark.parametrize("entry,expected", [
    ({"stage": "INGEST", "category": "NON_RECOVERABLE", "lastError": "esquema"}, ("ENTRADA_INVALIDA", "CORREGIR_EN_ORIGEN")),
    ({"stage": "PROVIDER", "category": "NON_RECOVERABLE", "lastError": "Rechazo del proveedor alfa (CAT-01): Catálogo del proveedor actualizado"},
     ("RECHAZO_CATALOGO_PROVEEDOR", "REPROCESAR_TRAS_CORRECCION")),
    ({"stage": "PROVIDER", "category": "RECOVERABLE", "lastError": "Proveedor beta no disponible: HTTP 503"},
     ("PROVEEDOR_NO_DISPONIBLE", "REPROCESAR_CUANDO_SE_RECUPERE")),
    ({"stage": "PROVIDER", "category": "NON_RECOVERABLE", "lastError": "VAL-R-D1-01",
      "findings": json.dumps([{"ruleId": "R-D1-01", "type": "D1"}])}, ("DEFECTO_D1", "CORREGIR_EN_ORIGEN")),
])
def test_triage_taxonomy(entry, expected):
    t = classify(entry)
    assert (t["classification"], t["recommendation"]) == expected
    assert t["evidence"]


def test_mcp_servers_are_least_privilege(sandbox):
    tools = {name: {t.name for t in asyncio.run(s.list_tools())} for name, s in mcp_servers.SERVERS.items()}
    assert tools["repo"] == {"crear_rama", "escribir_archivo", "abrir_solicitud", "listar_solicitudes"}
    assert not any("merge" in t or "fusionar" in t for ts in tools.values() for t in ts)
    hits = mcp_servers.call(mcp_servers.spec_reader, "buscar_especificacion",
                            {"especificacion": "proveedor-gamma-v1", "consulta": "correo del comprador obligatorio"})
    assert hits[0]["sourceSha256"] == spec("proveedor-gamma-v1").sha256
    b = mcp_servers.call(mcp_servers.repo, "crear_rama", {"nombre": "prueba"})["branch"]
    bad = mcp_servers.call(mcp_servers.repo, "escribir_archivo", {"rama": b, "ruta": "../../fuera.txt", "contenido": "x"})
    assert "error" in bad
    assert not (sandbox.parent / "fuera.txt").exists()


def test_cycle_ends_pending_review_and_never_decides(temp_db, sandbox, fake_platform):
    r = run_cycle("gamma", "XX", backend=SpecHeuristicBackend("gamma", "XX"), platform=fake_platform)
    assert r["status"] == "PENDING_REVIEW" and r["registry"]["status"] == "CANDIDATE"
    assert [c[0] for c in fake_platform.calls if c[0] != "check"] == ["propose"]
    pr = json.loads((sandbox / "pulls" / f"{r['pullRequest']}.json").read_text(encoding="utf-8"))
    assert pr["status"] == "OPEN" and pr["merged"] is False
    assert proposals.get(r["proposalId"])["registry_version"] == 2


def test_cycle_with_kit_block_ends_blocked(temp_db, sandbox, fake_platform):
    fake_platform.passed = False
    r = run_cycle("gamma", "XX", backend=SpecHeuristicBackend("gamma", "XX"), platform=fake_platform)
    assert r["status"] == "BLOCKED_BY_GATE" and r["registry"]["status"] == "BLOCKED"
    assert r["attempts"] == 1  # el motor heurístico no reintenta


@pytest.fixture()
def tokens(tmp_path, monkeypatch):
    people = {"reviewer-a": "REVIEWER", "agente.mapeo": "AGENT"}
    table = {s: secrets.token_urlsafe(32) for s in people}
    path = tmp_path / "demo-tokens.json"
    path.write_text(json.dumps({"tokens": [{"subject": s, "role": r, "token": table[s]} for s, r in people.items()]}),
                    encoding="utf-8")
    monkeypatch.setenv("VERIAGENT_TOKENS_FILE", str(path))
    return table


def test_human_decision_is_forwarded_with_the_reviewer_token(temp_db, tokens, monkeypatch):
    seen = []

    class FakeForwarder:
        def __init__(self, token):
            seen.append(token)
        def approve(self, *a):
            seen.append(("approve",) + a)
        def reject(self, *a):
            seen.append(("reject",) + a)

    monkeypatch.setattr(review_api, "ReviewerForwarder", FakeForwarder)
    pid = proposals.save("agente", "mapping", {"entries": []}, {"gatePassed": True}, True, registry=("gamma", "XX", 2))
    r = TestClient(review_api.app).post(f"/api/proposals/{pid}/decision", json={"decision": "APPROVE", "comment": "ok"},
                                        headers={"Authorization": f"Bearer {tokens['reviewer-a']}"})
    assert r.status_code == 200 and r.json()["reviewer"] == "reviewer-a"
    assert seen == [tokens["reviewer-a"], ("approve", "gamma", "XX", 2, "ok")]


def test_registry_conflict_blocks_the_local_approval(temp_db, tokens, monkeypatch):
    class Refusing:
        def __init__(self, token):
            pass
        def approve(self, *a):
            raise PlatformError(409, {"message": "No se puede aprobar: la versión 2 no pasó el kit de verificación"})

    monkeypatch.setattr(review_api, "ReviewerForwarder", Refusing)
    pid = proposals.save("agente", "mapping", {"entries": []}, {"gatePassed": True}, True, registry=("gamma", "XX", 2))
    r = TestClient(review_api.app).post(f"/api/proposals/{pid}/decision", json={"decision": "APPROVE"},
                                        headers={"Authorization": f"Bearer {tokens['reviewer-a']}"})
    assert r.status_code == 409 and "kit" in r.json()["detail"]
    assert proposals.get(pid)["status"] == "PENDING_REVIEW"


@pytest.mark.parametrize("name", ["C:secretos", "../README", "..\\README", "perfil-alfa-v2.1.md/..", ""])
def test_spec_names_are_a_whitelist(name):
    with pytest.raises((KeyError, ValueError)):
        spec(name)


@pytest.mark.parametrize("provider,country", [("alfa/CO/versions/1/approve#", "CO"), ("alfa", "CO/../x"), ("ALFA?", "CO")])
def test_platform_paths_reject_injection(provider, country):
    from tfg_agents.platform_client import PlatformClient
    with pytest.raises(ValueError):
        PlatformClient(token="t", url="http://127.0.0.1:9").check_mapping(provider, country, {})
