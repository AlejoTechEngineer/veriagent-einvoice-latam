"""API de revisión humana. Sirve también la consola web (HTML y CSS puro).

Regla de oro (ADR-04 y ADR-05): una propuesta que no pasó el kit no se puede aprobar (HTTP 409).
"""
from __future__ import annotations

from pathlib import Path
from typing import Literal

from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from . import POC_HOME, proposals
from .mapping import load_mapping

WEB = POC_HOME / "review-console-web"
GOLDEN = POC_HOME / "agentic-plane" / "golden" / "mapping-alfa-v2.json"

app = FastAPI(title="TFG · API de revisión de propuestas de IA", version="0.1.0")


class Decision(BaseModel):
    decision: Literal["APPROVE", "REJECT"]
    reviewer: str = Field(min_length=3, max_length=80)
    comment: str = Field(default="", max_length=1000)


@app.get("/api/health")
def health() -> dict:
    return {"status": "UP"}


@app.get("/api/proposals")
def list_proposals() -> list[dict]:
    return proposals.list_all()


@app.get("/api/proposals/{pid}")
def get_proposal(pid: str) -> dict:
    p = proposals.get(pid)
    if p is None:
        raise HTTPException(404, "Propuesta no encontrada")
    current = {e["target"]: e for e in load_mapping(GOLDEN)["entries"]}
    diff = []
    for e in p["mapping"]["entries"]:
        old = current.get(e["target"])
        strip = lambda d: {k: v for k, v in (d or {}).items() if k != "evidence"}
        if strip(old) != strip(e):
            diff.append({"target": e["target"], "current": strip(old) or None, "proposed": strip(e),
                         "evidence": e.get("evidence")})
    p["diff"] = diff
    return p


@app.post("/api/proposals/{pid}/decision")
def decide(pid: str, body: Decision) -> dict:
    try:
        return proposals.decide(pid, body.decision, body.reviewer, body.comment)
    except KeyError:
        raise HTTPException(404, "Propuesta no encontrada")
    except proposals.GateError as e:
        raise HTTPException(409, str(e))
    except proposals.StateError as e:
        raise HTTPException(409, str(e))


@app.get("/")
def index() -> FileResponse:
    return FileResponse(WEB / "index.html")


if WEB.exists():
    app.mount("/static", StaticFiles(directory=WEB), name="static")
