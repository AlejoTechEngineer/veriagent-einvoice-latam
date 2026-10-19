"""Servidor MCP del plano agéntico: especificaciones con procedencia, catálogos y validador.

Los agentes no leen archivos ni llaman al kit directamente: lo hacen a través de estas herramientas,
que son de solo lectura y dejan traza de la sección y el hash del documento consultado.
"""
from __future__ import annotations

import csv
import json
import subprocess
import tempfile
from pathlib import Path

from mcp.server.mcpserver import MCPServer

from . import POC_HOME
from .kit_client import kit_command
from .spec_reader import search, sha256

server = MCPServer(name="tfg-facturacion", instructions=(
    "Herramientas de solo lectura para proponer mapeos de facturación electrónica. Cita siempre la "
    "sección y el hash devueltos. Ninguna herramienta despliega ni escribe en producción."))

CATALOGS = POC_HOME / "catalogs" / "catalogos-alfa-v2.csv"


@server.tool(description="Busca secciones de la especificación del perfil y devuelve texto con procedencia.")
def buscar_especificacion(consulta: str, max_resultados: int = 3) -> str:
    return json.dumps(search(consulta, max(1, min(max_resultados, 5))), ensure_ascii=False)


@server.tool(description="Devuelve un catálogo oficial versionado con la sección de la norma que lo define.")
def obtener_catalogo(nombre: str) -> str:
    rows = [r for r in csv.DictReader(l for l in CATALOGS.read_text(encoding="utf-8").splitlines()
                                      if not l.startswith("#")) if r["catalogo"] == nombre]
    if not rows:
        return json.dumps({"error": f"catálogo desconocido: {nombre}"}, ensure_ascii=False)
    return json.dumps({"catalogo": nombre, "version": rows[0]["version"], "seccion": rows[0]["seccion"],
                       "sourceSha256": sha256(),
                       "entradas": [{"codigo": r["codigo"], "etiqueta": r["etiqueta"]} for r in rows]},
                      ensure_ascii=False)


@server.tool(description="Valida un comprobante con el kit determinista. formato: xml o seg.")
def validar_comprobante(contenido: str, formato: str = "xml") -> str:
    if formato not in ("xml", "seg"):
        return json.dumps({"error": "formato debe ser xml o seg"})
    if len(contenido) > 2_000_000:
        return json.dumps({"error": "documento demasiado grande"})
    with tempfile.NamedTemporaryFile("w", suffix=f".{formato}", delete=False, encoding="utf-8") as f:
        f.write(contenido)
    r = subprocess.run(kit_command() + ["verify", "--home", str(POC_HOME), "--format", formato,
                                        "--mode", "RUNTIME", f.name], capture_output=True, text=True)
    Path(f.name).unlink(missing_ok=True)
    return r.stdout.strip()


if __name__ == "__main__":
    server.run()  # transporte stdio
