"""Salida de red del plano agéntico: lista blanca de destinos.

Todo el tráfico HTTP de los agentes pasa por `client()` o `request()`. Un gancho de httpx revisa cada petición,
también las de las redirecciones, antes de abrir la conexión, y rechaza cualquier origen (esquema, host y puerto)
que no esté en la lista:
  - el kit: en el prototipo lo sirve la plataforma (`VERIAGENT_URL`, que también aloja el registro de mapeos, D-01)
    y, si existe, el servicio del kit aparte (`TFG_KIT_URL`);
  - el proveedor del modelo configurado, solo si hay un modelo configurado (`ANTHROPIC_API_KEY`):
    `ANTHROPIC_BASE_URL` o `https://api.anthropic.com`.
La lista se calcula de la configuración del proceso, nunca del contenido que procesa el agente.
"""
from __future__ import annotations

import os
from typing import Any
from urllib.parse import urlsplit

import httpx

DEFAULT_PLATFORM_URL = "http://127.0.0.1:8080"
DEFAULT_LLM_URL = "https://api.anthropic.com"
_DEFAULT_PORTS = {"http": 80, "https": 443}


class EgressDenied(Exception):
    """Destino fuera de la lista blanca de salida del plano agéntico."""

    def __init__(self, url: str, allowed: set[tuple[str, str, int]]):
        shown = ", ".join(f"{s}://{h}:{p}" for s, h, p in sorted(allowed)) or "ninguno"
        super().__init__(f"Salida denegada a {url}: destino fuera de la lista blanca ({shown})")
        self.url = url


def origin(url: str) -> tuple[str, str, int]:
    parts = urlsplit(str(url))
    scheme = (parts.scheme or "").lower()
    host = (parts.hostname or "").lower()
    if scheme not in _DEFAULT_PORTS or not host:
        raise ValueError(f"URL no válida: {url!r}")
    return scheme, host, parts.port or _DEFAULT_PORTS[scheme]


def llm_configured() -> bool:
    return bool(os.environ.get("ANTHROPIC_API_KEY"))


def allowed_origins() -> set[tuple[str, str, int]]:
    urls = [os.environ.get("VERIAGENT_URL", DEFAULT_PLATFORM_URL)]
    if os.environ.get("TFG_KIT_URL"):
        urls.append(os.environ["TFG_KIT_URL"])
    if llm_configured():
        urls.append(os.environ.get("ANTHROPIC_BASE_URL") or DEFAULT_LLM_URL)
    return {origin(u) for u in urls}


def check(url: str) -> None:
    allowed = allowed_origins()
    try:
        target = origin(url)
    except ValueError:
        raise EgressDenied(str(url), allowed) from None
    if target not in allowed:
        raise EgressDenied(str(url), allowed)


def _guard(request: httpx.Request) -> None:
    check(str(request.url))


def _guarded(lib: Any, kw: dict[str, Any]) -> Any:
    hooks = kw.pop("event_hooks", {}) or {}
    hooks = {**hooks, "request": [_guard, *hooks.get("request", [])]}
    return lib.Client(event_hooks=hooks, **kw)


def client(**kw: Any) -> httpx.Client:
    """Cliente httpx con la lista blanca aplicada a cada petición."""
    return _guarded(httpx, kw)


def sdk_client(**kw: Any) -> Any:
    """Cliente HTTP para el SDK del modelo, con la misma lista blanca. Las versiones recientes del SDK usan `httpx2`
    (misma API que httpx) y rechazan un cliente de httpx; las anteriores usan httpx."""
    try:
        import httpx2 as lib
    except ImportError:
        lib = httpx
    return _guarded(lib, kw)


def request(method: str, url: str, **kw: Any) -> httpx.Response:
    check(url)
    timeout = kw.pop("timeout", 30.0)
    with client(timeout=timeout) as c:
        return c.request(method, url, **kw)
