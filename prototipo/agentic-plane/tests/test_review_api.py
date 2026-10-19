from fastapi.testclient import TestClient

from tfg_agents import proposals
from tfg_agents.review_api import app


def _seed():
    blocked = proposals.save("agente", "mapping", {"entries": [], "specSha256": "x"}, {"gatePassed": False}, False)
    ok = proposals.save("agente", "mapping", {"entries": [], "specSha256": "x"}, {"gatePassed": True}, True)
    return blocked, ok


def test_cannot_approve_a_proposal_that_failed_the_gate(temp_db):
    blocked, _ = _seed()
    r = TestClient(app).post(f"/api/proposals/{blocked}/decision", json={"decision": "APPROVE", "reviewer": "lider.tecnico"})
    assert r.status_code == 409
    assert "kit" in r.json()["detail"]


def test_approve_then_second_decision_conflicts(temp_db):
    _, ok = _seed()
    c = TestClient(app)
    r = c.post(f"/api/proposals/{ok}/decision", json={"decision": "APPROVE", "reviewer": "lider.tecnico", "comment": "ok"})
    assert r.status_code == 200 and r.json()["status"] == "APPROVED" and r.json()["reviewer"] == "lider.tecnico"
    assert c.post(f"/api/proposals/{ok}/decision", json={"decision": "REJECT", "reviewer": "otra.persona"}).status_code == 409


def test_reviewer_is_mandatory_and_proposal_must_exist(temp_db):
    _, ok = _seed()
    c = TestClient(app)
    assert c.post(f"/api/proposals/{ok}/decision", json={"decision": "APPROVE", "reviewer": ""}).status_code == 422
    assert c.get("/api/proposals/no-existe").status_code == 404
    assert len(c.get("/api/proposals").json()) == 2
