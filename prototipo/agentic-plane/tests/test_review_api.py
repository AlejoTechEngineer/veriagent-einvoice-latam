import json
import secrets

import pytest
from fastapi.testclient import TestClient

from tfg_agents import proposals
from tfg_agents.review_api import app


@pytest.fixture()
def tokens(tmp_path, monkeypatch):
    """Tokens aleatorios en un archivo temporal, con el mismo formato que genera la plataforma Java."""
    people = {"reviewer-a": "REVIEWER", "reviewer-b": "REVIEWER", "agente.mapeo": "AGENT", "operador.demo": "OPERATOR"}
    table = {s: secrets.token_urlsafe(32) for s in people}
    path = tmp_path / "demo-tokens.json"
    path.write_text(json.dumps({"tokens": [{"subject": s, "role": r, "token": table[s]} for s, r in people.items()]}),
                    encoding="utf-8")
    monkeypatch.setenv("VERIAGENT_TOKENS_FILE", str(path))
    return {s: {"Authorization": f"Bearer {t}"} for s, t in table.items()}


def _seed():
    blocked = proposals.save("agente", "mapping", {"entries": [], "specSha256": "x"}, {"gatePassed": False}, False)
    ok = proposals.save("agente", "mapping", {"entries": [], "specSha256": "x"}, {"gatePassed": True}, True)
    return blocked, ok


def test_without_token_everything_is_401(temp_db, tokens):
    _, ok = _seed()
    c = TestClient(app)
    assert c.post(f"/api/proposals/{ok}/decision", json={"decision": "APPROVE"}).status_code == 401
    assert c.post(f"/api/proposals/{ok}/decision", json={"decision": "APPROVE"},
                  headers={"Authorization": "Bearer token-inventado"}).status_code == 401
    assert c.get("/api/proposals").status_code == 401
    assert c.get(f"/api/proposals/{ok}").status_code == 401
    assert c.get("/api/me").status_code == 401
    assert c.get("/api/health").status_code == 200
    assert proposals.get(ok)["status"] == "PENDING_REVIEW"


def test_agent_and_operator_cannot_decide(temp_db, tokens):
    _, ok = _seed()
    c = TestClient(app)
    for who in ("agente.mapeo", "operador.demo"):
        r = c.post(f"/api/proposals/{ok}/decision", json={"decision": "APPROVE"}, headers=tokens[who])
        assert r.status_code == 403, who
    assert proposals.get(ok)["status"] == "PENDING_REVIEW"
    assert c.get("/api/proposals", headers=tokens["agente.mapeo"]).status_code == 200


def test_cannot_approve_a_proposal_that_failed_the_gate(temp_db, tokens):
    blocked, _ = _seed()
    r = TestClient(app).post(f"/api/proposals/{blocked}/decision", json={"decision": "APPROVE"}, headers=tokens["reviewer-a"])
    assert r.status_code == 409
    assert "kit" in r.json()["detail"]


def test_reviewer_identity_comes_from_the_token(temp_db, tokens):
    _, ok = _seed()
    c = TestClient(app)
    r = c.post(f"/api/proposals/{ok}/decision", json={"decision": "APPROVE", "comment": "ok"}, headers=tokens["reviewer-a"])
    assert r.status_code == 200 and r.json()["status"] == "APPROVED" and r.json()["reviewer"] == "reviewer-a"
    assert r.json()["decided_at"]
    assert c.post(f"/api/proposals/{ok}/decision", json={"decision": "REJECT"}, headers=tokens["reviewer-b"]).status_code == 409


def test_impersonation_in_body_has_no_effect(temp_db, tokens):
    _, ok = _seed()
    c = TestClient(app)
    r = c.post(f"/api/proposals/{ok}/decision", json={"decision": "APPROVE", "reviewer": "reviewer-b"}, headers=tokens["reviewer-a"])
    assert r.status_code == 403
    assert proposals.get(ok)["status"] == "PENDING_REVIEW" and proposals.get(ok)["reviewer"] is None
    r = c.post(f"/api/proposals/{ok}/decision", json={"decision": "APPROVE", "reviewer": "reviewer-a"}, headers=tokens["reviewer-a"])
    assert r.status_code == 200 and r.json()["reviewer"] == "reviewer-a"


def test_me_reports_the_token_identity(temp_db, tokens):
    r = TestClient(app).get("/api/me", headers=tokens["reviewer-b"])
    assert r.json() == {"subject": "reviewer-b", "role": "REVIEWER"}


def test_headers_do_not_change_identity(temp_db, tokens):
    _, ok = _seed()
    h = dict(tokens["reviewer-a"], **{"X-Reviewer": "reviewer-b", "X-User": "reviewer-b"})
    r = TestClient(app).post(f"/api/proposals/{ok}/decision", json={"decision": "REJECT"}, headers=h)
    assert r.status_code == 200 and r.json()["reviewer"] == "reviewer-a"


def test_proposal_must_exist(temp_db, tokens):
    _seed()
    c = TestClient(app)
    assert c.get("/api/proposals/no-existe", headers=tokens["reviewer-a"]).status_code == 404
    assert c.post("/api/proposals/no-existe/decision", json={"decision": "APPROVE"}, headers=tokens["reviewer-a"]).status_code == 404
    assert len(c.get("/api/proposals", headers=tokens["reviewer-a"]).json()) == 2
