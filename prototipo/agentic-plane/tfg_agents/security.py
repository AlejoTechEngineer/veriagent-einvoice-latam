"""Seguridad del plano agéntico: el mismo modelo de roles y tokens que el plano de integración.

Los tokens de demostración los genera la plataforma Java al arrancar (``results/runtime/demo-tokens.json``,
ignorado por git). Este módulo los lee del mismo archivo, de modo que un token vale igual en las dos APIs.
Reglas (SEGURIDAD.md): denegar por defecto; la identidad de quien decide sale siempre del token, nunca del
cuerpo ni de cabeceras editables; un agente nunca aprueba ni decide.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import os
import threading
from dataclasses import dataclass
from enum import Enum
from pathlib import Path

from fastapi import Depends, HTTPException
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from . import POC_HOME


class Role(str, Enum):
    REVIEWER = "REVIEWER"
    AGENT = "AGENT"
    OPERATOR = "OPERATOR"
    SYSTEM = "SYSTEM"


@dataclass(frozen=True)
class Actor:
    subject: str
    role: Role


def tokens_file() -> Path:
    env = os.environ.get("VERIAGENT_TOKENS_FILE")
    return Path(env) if env else POC_HOME / "results" / "runtime" / "demo-tokens.json"


def _digest(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


class TokenStore:
    """Tokens por su huella SHA-256; se recargan si el archivo cambia (la plataforma los regenera al arrancar)."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._source: tuple[str, float] | None = None
        self._by_digest: dict[str, Actor] = {}

    def _load(self) -> None:
        path = tokens_file()
        try:
            stamp = (str(path), path.stat().st_mtime)
        except OSError:
            self._source, self._by_digest = None, {}
            return
        if stamp == self._source:
            return
        doc = json.loads(path.read_text(encoding="utf-8"))
        table = {}
        for t in doc.get("tokens", []):
            try:
                table[_digest(t["token"])] = Actor(t["subject"], Role(t["role"]))
            except (KeyError, ValueError):
                continue
        self._source, self._by_digest = stamp, table

    def resolve(self, token: str) -> Actor | None:
        with self._lock:
            self._load()
            d = _digest(token)
            for known, actor in self._by_digest.items():
                if hmac.compare_digest(known, d):
                    return actor
        return None


_store = TokenStore()
_bearer = HTTPBearer(auto_error=False)


def current_actor(credentials: HTTPAuthorizationCredentials | None = Depends(_bearer)) -> Actor:
    """Identidad del token. Sin token, o con un token desconocido: 401."""
    if credentials is None or credentials.scheme.lower() != "bearer" or not credentials.credentials:
        raise HTTPException(401, "Falta el token de acceso", headers={"WWW-Authenticate": "Bearer"})
    actor = _store.resolve(credentials.credentials)
    if actor is None:
        raise HTTPException(401, "Token no válido", headers={"WWW-Authenticate": "Bearer"})
    return actor


def bearer_token(credentials: HTTPAuthorizationCredentials | None = Depends(_bearer)) -> str:
    """Token de la petición, para reenviar la decisión a la plataforma con la misma identidad."""
    if credentials is None or not credentials.credentials:
        raise HTTPException(401, "Falta el token de acceso", headers={"WWW-Authenticate": "Bearer"})
    return credentials.credentials


def require_role(*roles: Role):
    """Dependencia que exige uno de los roles; si no, 403."""
    def check(actor: Actor = Depends(current_actor)) -> Actor:
        if actor.role not in roles:
            raise HTTPException(403, "Tu rol no permite esta operación")
        return actor
    return check


def require_consistent(actor: Actor, claimed: str | None) -> None:
    """Un nombre de persona en el cuerpo solo se admite si coincide con el token (si no, 403: suplantación)."""
    if claimed is not None and claimed.strip() and claimed.strip() != actor.subject:
        raise HTTPException(403, "La identidad del cuerpo no coincide con la del token")
