"""Cliente de la plataforma de integración para los agentes.

El agente actúa con su propia identidad (rol AGENT): lee su token del archivo local de tokens de demostración
y nunca usa el de una persona. La plataforma le impide aprobar, activar o reprocesar (403); aquí no hay
ninguna función para hacerlo.
"""
from __future__ import annotations

import json
import os
from typing import Any

import httpx

from .security import Role, tokens_file


class PlatformError(Exception):
    def __init__(self, status: int, body: Any):
        super().__init__(f"HTTP {status}: {body}")
        self.status = status
        self.body = body


def base_url() -> str:
    return os.environ.get("VERIAGENT_URL", "http://127.0.0.1:8080").rstrip("/")


def token_for(subject: str | None = None, role: Role = Role.AGENT) -> str:
    """Token de una identidad del archivo local (por defecto, la del agente)."""
    doc = json.loads(tokens_file().read_text(encoding="utf-8"))
    for t in doc.get("tokens", []):
        if t.get("role") == role.value and (subject is None or t.get("subject") == subject):
            return t["token"]
    raise KeyError(f"No hay token para {subject or role.value} en {tokens_file()}")


class PlatformClient:
    def __init__(self, token: str | None = None, url: str | None = None, timeout: float = 120.0):
        self.url = (url or base_url()).rstrip("/")
        self._token = token
        self.timeout = timeout

    @property
    def token(self) -> str:
        if self._token is None:
            self._token = token_for(os.environ.get("VERIAGENT_AGENT_SUBJECT"), Role.AGENT)
        return self._token

    def _call(self, method: str, path: str, **kw) -> Any:
        r = httpx.request(method, self.url + path, headers={"Authorization": f"Bearer {self.token}"},
                          timeout=self.timeout, **kw)
        body = r.json() if r.content and r.headers.get("content-type", "").startswith("application/json") else r.text
        if r.status_code >= 400:
            raise PlatformError(r.status_code, body)
        return body

    # Registro de mapeos
    def check_mapping(self, provider: str, country: str, mapping: dict) -> dict:
        return self._call("POST", f"/api/mappings/{provider}/{country}/check", json={"mapping": mapping})

    def propose_mapping(self, provider: str, country: str, mapping: dict, source: str, ref: str | None) -> dict:
        return self._call("POST", f"/api/mappings/{provider}/{country}/candidates",
                          json={"mapping": mapping, "source": source, "proposalRef": ref})

    def mapping_versions(self, provider: str, country: str) -> list[dict]:
        return self._call("GET", f"/api/mappings/{provider}/{country}/versions")

    # Kit
    def verify_document(self, profile: str, content: str, mode: str = "RUNTIME") -> dict:
        r = httpx.post(self.url + "/api/kit/verify", params={"profile": profile, "mode": mode},
                       headers={"Authorization": f"Bearer {self.token}", "Content-Type": "text/plain; charset=utf-8"},
                       content=content.encode("utf-8"), timeout=self.timeout)
        if r.status_code not in (200, 422):
            raise PlatformError(r.status_code, r.text)
        return r.json()

    # DLQ (solo lectura y triaje)
    def dlq_open(self, limit: int = 200) -> list[dict]:
        return self._call("GET", f"/api/dlq?status=OPEN&limit={limit}")

    def save_triage(self, entry_id: int, triage: dict) -> dict:
        return self._call("POST", f"/api/dlq/{entry_id}/triage", json=triage)


class ReviewerForwarder:
    """Reenvía a la plataforma la decisión de una persona con SU token (la identidad sale de él, no del agente)."""

    def __init__(self, bearer_token: str, url: str | None = None):
        self.client = PlatformClient(token=bearer_token, url=url)

    def approve(self, provider: str, country: str, version: int, note: str) -> dict:
        return self.client._call("POST", f"/api/mappings/{provider}/{country}/versions/{version}/approve", json={"note": note})

    def reject(self, provider: str, country: str, version: int, note: str) -> dict:
        return self.client._call("POST", f"/api/mappings/{provider}/{country}/versions/{version}/reject", json={"note": note})
