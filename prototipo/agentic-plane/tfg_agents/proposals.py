"""Registro de propuestas de IA (tabla agent_proposal): verificación, revisor y decisión, para auditoría."""
from __future__ import annotations

import json
import os
import sqlite3
import uuid
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path

from . import POC_HOME

SCHEMA = """
CREATE TABLE IF NOT EXISTS agent_proposal (
  id TEXT PRIMARY KEY,
  agent TEXT NOT NULL,
  kind TEXT NOT NULL,
  created_at TEXT NOT NULL,
  spec_sha256 TEXT NOT NULL,
  mapping_json TEXT NOT NULL,
  verification_json TEXT NOT NULL,
  gate_passed INTEGER NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('BLOCKED_BY_GATE','PENDING_REVIEW','APPROVED','REJECTED')),
  reviewer TEXT,
  decision_comment TEXT,
  decided_at TEXT
);
CREATE INDEX IF NOT EXISTS ix_proposal_status ON agent_proposal(status);
"""


def db_path() -> Path:
    return Path(os.environ.get("TFG_DB", POC_HOME / "results" / "proposals.db"))


@contextmanager
def connect():
    p = db_path()
    p.parent.mkdir(parents=True, exist_ok=True)
    con = sqlite3.connect(p)
    con.row_factory = sqlite3.Row
    con.executescript(SCHEMA)
    try:
        yield con
        con.commit()
    finally:
        con.close()


def save(agent: str, kind: str, mapping: dict, verification: dict, gate_passed: bool) -> str:
    pid = f"prop-{uuid.uuid4().hex[:8]}"
    with connect() as con:
        con.execute("INSERT INTO agent_proposal VALUES (?,?,?,?,?,?,?,?,?,NULL,NULL,NULL)",
                    (pid, agent, kind, datetime.now(timezone.utc).isoformat(timespec="seconds"),
                     mapping.get("specSha256", ""), json.dumps(mapping, ensure_ascii=False),
                     json.dumps(verification, ensure_ascii=False), int(gate_passed),
                     "PENDING_REVIEW" if gate_passed else "BLOCKED_BY_GATE"))
    return pid


def get(pid: str) -> dict | None:
    with connect() as con:
        row = con.execute("SELECT * FROM agent_proposal WHERE id=?", (pid,)).fetchone()
    if row is None:
        return None
    d = dict(row)
    d["mapping"] = json.loads(d.pop("mapping_json"))
    d["verification"] = json.loads(d.pop("verification_json"))
    d["gate_passed"] = bool(d["gate_passed"])
    return d


def list_all() -> list[dict]:
    with connect() as con:
        rows = con.execute("SELECT id, agent, kind, created_at, gate_passed, status, reviewer, decided_at "
                           "FROM agent_proposal ORDER BY created_at DESC").fetchall()
    return [dict(r) | {"gate_passed": bool(r["gate_passed"])} for r in rows]


class GateError(Exception):
    """Se intentó aprobar una propuesta que no pasó la verificación determinista."""


class StateError(Exception):
    """La propuesta ya tiene una decisión."""


def decide(pid: str, decision: str, reviewer: str, comment: str = "") -> dict:
    p = get(pid)
    if p is None:
        raise KeyError(pid)
    if p["status"] in ("APPROVED", "REJECTED"):
        raise StateError(f"La propuesta ya fue {p['status']}")
    if decision == "APPROVE" and not p["gate_passed"]:
        raise GateError("No se puede aprobar: la propuesta no pasó el kit de verificación")
    status = "APPROVED" if decision == "APPROVE" else "REJECTED"
    with connect() as con:
        con.execute("UPDATE agent_proposal SET status=?, reviewer=?, decision_comment=?, decided_at=? WHERE id=?",
                    (status, reviewer, comment, datetime.now(timezone.utc).isoformat(timespec="seconds"), pid))
    return get(pid)
