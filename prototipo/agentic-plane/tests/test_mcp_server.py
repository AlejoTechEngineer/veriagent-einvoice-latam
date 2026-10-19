import asyncio
import json

from tfg_agents.mcp_server import server
from tfg_agents.spec_reader import sha256


def call(name, args):
    r = asyncio.run(server.call_tool(name, args))
    return json.loads(r.content[0].text)


def test_tools_are_exposed():
    names = {t.name for t in asyncio.run(server.list_tools())}
    assert names == {"buscar_especificacion", "obtener_catalogo", "validar_comprobante"}


def test_spec_search_returns_provenance():
    hits = call("buscar_especificacion", {"consulta": "correo electrónico obligatorio adquirente"})
    assert hits[0]["section"] == "§4.3"
    assert hits[0]["sourceSha256"] == sha256()


def test_validator_tool_reports_findings_with_source():
    bad = '<Invoice xmlns="urn:tfg:alfa:invoice:2"><ProfileID>x</ProfileID></Invoice>'
    report = call("validar_comprobante", {"contenido": bad, "formato": "xml"})
    assert report["passed"] is False
    assert any(f["source"].startswith("perfil-alfa-v2.1.md") for f in report["findings"])
