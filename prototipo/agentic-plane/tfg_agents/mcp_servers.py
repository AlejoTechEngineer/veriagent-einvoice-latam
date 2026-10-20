"""Servidores MCP del plano agéntico (tabla 12): mcp-spec-reader, mcp-catalog, mcp-validator y mcp-repo.

Son cuatro servidores independientes, cada uno con mínimo privilegio:
- spec-reader y catalog: solo lectura, con la sección y la huella SHA-256 del documento fuente.
- validator: expone el kit (por la API de la plataforma, con el token del agente); no cambia estado.
- repo: crea ramas y solicitudes de cambio en un repositorio de juguete (``data/sandbox-repo``), nunca en el
  repositorio real, y no tiene ninguna herramienta de merge.
Ejecución por stdio: ``python -m tfg_agents.mcp_servers <spec-reader|catalog|validator|repo>``.
"""
from __future__ import annotations

import asyncio
import csv
import hashlib
import json
import os
import re
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from mcp.server.mcpserver import MCPServer

from . import POC_HOME
from .platform_client import PlatformClient, PlatformError
from .specs import all_specs, spec

CATALOGS_DIR = POC_HOME / "catalogs"
MAX_FILE = 512 * 1024


def _json(v: Any) -> str:
    return json.dumps(v, ensure_ascii=False)


def call(server: MCPServer, name: str, args: dict) -> Any:
    """Invoca una herramienta MCP en el mismo proceso y devuelve su resultado JSON."""
    r = asyncio.run(server.call_tool(name, args))
    return json.loads(r.content[0].text)


# --- mcp-spec-reader -------------------------------------------------------------------------------------
spec_reader = MCPServer(name="mcp-spec-reader", instructions=(
    "Búsqueda sobre las especificaciones de los proveedores. Cada resultado trae la sección y la huella SHA-256 "
    "del documento: cítalas en toda propuesta. Solo lectura."))


@spec_reader.tool(description="Lista las especificaciones disponibles con su huella SHA-256.")
def listar_especificaciones() -> str:
    return _json([{"spec": s.path.name, "sha256": s.sha256} for s in all_specs()])


@spec_reader.tool(description="Busca en una especificación y devuelve secciones con procedencia (sección y huella).")
def buscar_especificacion(especificacion: str, consulta: str, max_resultados: int = 3) -> str:
    try:
        return _json(spec(especificacion).search(consulta, max(1, min(max_resultados, 5))))
    except (KeyError, ValueError) as e:
        return _json({"error": str(e)})


@spec_reader.tool(description="Devuelve las filas de campos (ruta, descripción, tipo, catálogo, sección) de una especificación.")
def campos_especificacion(especificacion: str) -> str:
    try:
        s = spec(especificacion)
    except (KeyError, ValueError) as e:
        return _json({"error": str(e)})
    return _json({"spec": s.path.name, "sha256": s.sha256,
                  "campos": [r.__dict__ for r in s.field_rows()]})


# --- mcp-catalog -----------------------------------------------------------------------------------------
catalog = MCPServer(name="mcp-catalog", instructions=(
    "Catálogos oficiales versionados por país y proveedor, con la sección de la norma que los define. Solo lectura."))


def _catalog_files() -> list[Path]:
    return sorted(CATALOGS_DIR.glob("catalogos-*.csv"))


@catalog.tool(description="Lista los archivos de catálogos y los catálogos que contiene cada uno.")
def listar_catalogos() -> str:
    out = []
    for f in _catalog_files():
        rows = list(csv.DictReader(l for l in f.read_text(encoding="utf-8").splitlines() if not l.startswith("#")))
        out.append({"archivo": f.name, "sha256": hashlib.sha256(f.read_bytes()).hexdigest(),
                    "catalogos": sorted({r["catalogo"] for r in rows})})
    return _json(out)


@catalog.tool(description="Devuelve un catálogo (código y etiqueta) de un archivo de catálogos, con versión y sección.")
def obtener_catalogo(archivo: str, nombre: str) -> str:
    f = CATALOGS_DIR / archivo
    if f not in _catalog_files():
        return _json({"error": f"archivo de catálogos desconocido: {archivo}"})
    rows = [r for r in csv.DictReader(l for l in f.read_text(encoding="utf-8").splitlines() if not l.startswith("#"))
            if r["catalogo"] == nombre]
    if not rows:
        return _json({"error": f"catálogo desconocido: {nombre}"})
    return _json({"archivo": archivo, "catalogo": nombre, "version": rows[0]["version"], "seccion": rows[0]["seccion"],
                  "sourceSha256": hashlib.sha256(f.read_bytes()).hexdigest(),
                  "entradas": [{"codigo": r["codigo"], "etiqueta": r["etiqueta"], "extra": r["extra"]} for r in rows]})


# --- mcp-validator ---------------------------------------------------------------------------------------
validator = MCPServer(name="mcp-validator", instructions=(
    "Expone el kit de verificación para que el agente valide sus propias propuestas. No registra ni aprueba nada."))


def _platform() -> PlatformClient:
    return PlatformClient()


@validator.tool(description="Valida un documento con un perfil del kit (alfa, beta, beta-pe, gamma) en modo ejecución.")
def validar_documento(perfil: str, contenido: str) -> str:
    if len(contenido) > 2_000_000:
        return _json({"error": "documento demasiado grande"})
    try:
        return _json(_platform().verify_document(perfil, contenido))
    except PlatformError as e:
        return _json({"error": str(e)})


@validator.tool(description="Aplica el control del kit a un mapeo candidato sin registrarlo (ejecución + integración continua).")
def verificar_mapeo(proveedor: str, pais: str, mapeo: dict) -> str:
    try:
        return _json(_platform().check_mapping(proveedor, pais, mapeo))
    except ValueError as e:
        return _json({"error": str(e)})
    except PlatformError as e:
        return _json({"error": str(e), "status": e.status})


# --- mcp-repo --------------------------------------------------------------------------------------------
repo = MCPServer(name="mcp-repo", instructions=(
    "Repositorio de juguete para proponer cambios: crea ramas, escribe archivos en ellas y abre solicitudes de "
    "cambio. No existe merge: integrar un cambio es una decisión humana fuera de este servidor."))


def sandbox_root() -> Path:
    return Path(os.environ.get("VERIAGENT_SANDBOX_REPO", POC_HOME / "data" / "sandbox-repo"))


def _safe(name: str) -> str:
    s = re.sub(r"[^A-Za-z0-9._-]", "-", name).strip(".-")[:80]
    if not s:
        raise ValueError("nombre vacío")
    return s


def _inside(base: Path, rel: str) -> Path:
    parts = [p for p in re.split(r"[\\/]+", rel) if p]
    if not parts or any(p in (".", "..") for p in parts):
        raise ValueError(f"ruta no permitida: {rel}")
    target = base.joinpath(*[_safe(p) for p in parts]).resolve()
    if base.resolve() not in target.parents:
        raise ValueError(f"ruta fuera del repositorio de juguete: {rel}")
    return target


@repo.tool(description="Crea una rama en el repositorio de juguete.")
def crear_rama(nombre: str, motivo: str = "") -> str:
    try:
        b = _safe(nombre)
    except ValueError as e:
        return _json({"error": str(e)})
    d = sandbox_root() / "branches" / b
    d.mkdir(parents=True, exist_ok=True)
    meta = d / ".branch.json"
    if not meta.exists():
        meta.write_text(_json({"branch": b, "base": "main", "reason": motivo,
                               "createdAt": datetime.now(timezone.utc).isoformat(timespec="seconds")}), encoding="utf-8")
    return _json({"branch": b, "path": str(d.relative_to(sandbox_root()))})


@repo.tool(description="Escribe un archivo de texto en una rama del repositorio de juguete.")
def escribir_archivo(rama: str, ruta: str, contenido: str) -> str:
    if len(contenido.encode("utf-8")) > MAX_FILE:
        return _json({"error": "archivo demasiado grande"})
    try:
        d = sandbox_root() / "branches" / _safe(rama)
        if not d.is_dir():
            return _json({"error": f"la rama no existe: {rama}"})
        f = _inside(d, ruta)
    except ValueError as e:
        return _json({"error": str(e)})
    f.parent.mkdir(parents=True, exist_ok=True)
    f.write_text(contenido, encoding="utf-8")
    return _json({"branch": _safe(rama), "file": str(f.relative_to(d)).replace("\\", "/"),
                  "sha256": hashlib.sha256(contenido.encode("utf-8")).hexdigest()})


@repo.tool(description="Abre una solicitud de cambio desde una rama. Queda abierta: no hay merge.")
def abrir_solicitud(rama: str, titulo: str, descripcion: str = "") -> str:
    try:
        b = _safe(rama)
    except ValueError as e:
        return _json({"error": str(e)})
    d = sandbox_root() / "branches" / b
    if not d.is_dir():
        return _json({"error": f"la rama no existe: {rama}"})
    pulls = sandbox_root() / "pulls"
    pulls.mkdir(parents=True, exist_ok=True)
    n = len(list(pulls.glob("PR-*.json"))) + 1
    files = sorted(str(p.relative_to(d)).replace("\\", "/") for p in d.rglob("*") if p.is_file() and p.name != ".branch.json")
    pr = {"id": f"PR-{n:04d}", "branch": b, "title": titulo[:200], "description": descripcion[:4000], "files": files,
          "status": "OPEN", "merged": False, "createdAt": datetime.now(timezone.utc).isoformat(timespec="seconds")}
    (pulls / f"PR-{n:04d}.json").write_text(_json(pr), encoding="utf-8")
    return _json(pr)


@repo.tool(description="Lista las solicitudes de cambio del repositorio de juguete.")
def listar_solicitudes() -> str:
    pulls = sandbox_root() / "pulls"
    return _json([json.loads(p.read_text(encoding="utf-8")) for p in sorted(pulls.glob("PR-*.json"))] if pulls.is_dir() else [])


SERVERS = {"spec-reader": spec_reader, "catalog": catalog, "validator": validator, "repo": repo}

if __name__ == "__main__":
    if len(sys.argv) != 2 or sys.argv[1] not in SERVERS:
        print("Uso: python -m tfg_agents.mcp_servers <" + "|".join(SERVERS) + ">", file=sys.stderr)
        sys.exit(64)
    SERVERS[sys.argv[1]].run()  # transporte stdio
