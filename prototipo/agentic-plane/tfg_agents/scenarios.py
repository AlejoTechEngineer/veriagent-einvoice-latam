"""Escenarios S3 a S8, banco de 22 defectos y mediciones, contra la plataforma real (perfil local en marcha).

Cada escenario actúa como lo haría una persona o un sistema: emite por la fachada, carga lotes por la ingesta,
activa fallos en los simuladores con el rol de operador, ejecuta los agentes con su propio token y decide con
los tokens de ``reviewer-a`` y ``reviewer-b``. Los resultados se escriben en ``results/escenarios/<id>.json``.
Uso: ``python -m tfg_agents.scenarios <s3|s4|s5|s6|s7|s8|banco|rendimiento|todos>``.
"""
from __future__ import annotations

import json
import logging
import os
import statistics
import subprocess
import sys
import tempfile
import time
from contextlib import contextmanager
from pathlib import Path
from typing import Any

import httpx

from . import POC_HOME, legacy_agent, triage_agent
from .agent_orchestrator import run_cycle
from .mapping import load_mapping
from .mutations import MUTANTS
from .platform_client import base_url
from .security import tokens_file

logging.getLogger("httpx").setLevel(logging.WARNING)

RESULTS = POC_HOME / "results" / "escenarios"
JAR = POC_HOME / "integration-plane" / "platform-app" / "target" / "platform-app-0.1.0.jar"
REVIEW_URL = "http://127.0.0.1:8090"


# --- utilidades -------------------------------------------------------------------------------------------
def _tokens() -> dict[str, str]:
    doc = json.loads(tokens_file().read_text(encoding="utf-8"))
    return {t["subject"]: t["token"] for t in doc["tokens"]}


T: dict[str, str] = {}
OPERATOR, AGENT, REV_A, REV_B = "operador.demo", "agente.mapeo", "reviewer-a", "reviewer-b"


def api(method: str, path: str, who: str | None, url: str | None = None, **kw) -> tuple[int, Any]:
    headers = kw.pop("headers", {})
    if who:
        headers["Authorization"] = f"Bearer {T[who]}"
    r = httpx.request(method, (url or base_url()) + path, headers=headers, timeout=600, **kw)
    try:
        body = r.json()
    except ValueError:
        body = r.text
    return r.status_code, body


def generate(*args: str) -> list[dict]:
    """Generador de datos de la plataforma (semilla fija), por la misma línea de órdenes que los scripts."""
    out = Path(tempfile.mkdtemp(prefix="veriagent-gen-")) / "lote.json"
    java = Path(os.environ["JAVA_HOME"]) / "bin" / "java" if os.environ.get("JAVA_HOME") else "java"
    subprocess.run([str(java), "-Dloader.main=org.veriagent.datagen.GeneratorCli", "-cp", str(JAR),
                    "org.springframework.boot.loader.launch.PropertiesLauncher", *args, "--home", str(POC_HOME),
                    "--out", str(out)], check=True, capture_output=True)
    return json.loads(out.read_text(encoding="utf-8"))


def upload(invoices: list[dict], name: str) -> dict:
    content = "".join(json.dumps(i, ensure_ascii=False) + "\n" for i in invoices).encode("utf-8")
    s, b = api("POST", "/api/ingest/batches?mode=now", OPERATOR, files={"file": (name, content, "application/json")})
    assert s in (200, 202), (s, b)
    return b


def stats() -> dict:
    return api("GET", "/api/invoices/stats", OPERATOR)[1]


def quiet(timeout: float = 600, min_registered: int = 0) -> dict:
    end = time.time() + timeout
    while True:
        s = stats()
        if s["conservation"]["inFlight"] == 0 and s["conservation"]["registered"] >= min_registered:
            return s
        if time.time() > end:
            return s
        time.sleep(0.5)


def status_of(key: str) -> str | None:
    s, b = api("GET", f"/api/invoices/{key}", OPERATOR)
    return b.get("status") if s == 200 else None


def sim_stats() -> dict:
    return api("GET", "/api/simulators", OPERATOR)[1]["stats"]


def received(provider: str) -> int:
    return sim_stats().get(provider, {}).get("received", 0)


def fault(provider: str, name: str | None, remaining: int = -1) -> None:
    if name is None:
        api("DELETE", f"/api/simulators/{provider}/faults", OPERATOR)
    else:
        s, b = api("POST", f"/api/simulators/{provider}/faults", OPERATOR, json={"fault": name, "remaining": remaining})
        assert s == 200, (s, b)


def emit(inv: dict, who: str = OPERATOR) -> tuple[int, dict]:
    return api("POST", "/api/invoices", who, json=inv)


def dlq_open() -> list[dict]:
    return api("GET", "/api/dlq?status=OPEN&limit=500", OPERATOR)[1]


def write(sid: str, result: dict, file: str | None = None) -> dict:
    RESULTS.mkdir(parents=True, exist_ok=True)
    result["aprobado"] = all(result["comprobaciones"].values())
    result["fecha"] = time.strftime("%Y-%m-%dT%H:%M:%S")
    (RESULTS / (file or f"{sid}.json")).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n{sid}: {result['descripcion']}")
    for k, v in result["comprobaciones"].items():
        print(f"  {k:<60} {'OK' if v else 'FALLA'}")
    return result


@contextmanager
def review_api():
    """API de revisión de propuestas en 127.0.0.1:8090 durante el escenario."""
    p = subprocess.Popen([sys.executable, "-m", "uvicorn", "tfg_agents.review_api:app", "--host", "127.0.0.1",
                          "--port", "8090"], cwd=POC_HOME / "agentic-plane", stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        for _ in range(120):
            try:
                if httpx.get(REVIEW_URL + "/api/health").status_code == 200:
                    break
            except httpx.HTTPError:
                time.sleep(0.25)
        yield
    finally:
        p.terminate()
        p.wait(10)


def pct(values: list[float], q: float) -> float:
    v = sorted(values)
    return round(v[min(len(v) - 1, int(round(q * (len(v) - 1))))], 2)


# --- S3: defectos D1 a D6 bloqueados antes del envío ----------------------------------------------------------
def s3() -> dict:
    before_alfa = received("alfa")
    sync = {}
    for d, inv in zip(["D1", "D4", "D2", "D3"], generate("defects", "--seed", "303", "--offset", "303000")):
        s, b = emit(inv)
        f = b.get("findings") or []
        sync[d] = {"http": s, "codigo": b.get("reasonCode") or b.get("code"), "estado": status_of(_key(inv)),
                   "hallazgos": [{k: x.get(k) for k in ("ruleId", "type", "source", "sourceSha256")} for x in f][:3]}
    st0 = stats()
    batch_inv = generate("defects", "--seed", "313", "--offset", "313000")
    up = upload(batch_inv, "S3-defectos.jsonl")
    st = quiet(min_registered=st0["conservation"]["registered"])
    batch = {d: status_of(_key(i)) for d, i in zip(["D1", "D4", "D2", "D3"], batch_inv)}
    mapping = {}
    for mid in ("D5-a", "D6-a"):
        m = next(x for x in MUTANTS if x.id == mid)
        prov, ctry, base = _bank_base(m.format)
        s, v = api("POST", f"/api/mappings/{prov}/{ctry}/candidates", AGENT,
                   json={"mapping": m.apply(base), "source": "escenario-S3", "proposalRef": mid})
        a, ab = api("POST", f"/api/mappings/{prov}/{ctry}/versions/{v['version']}/approve", REV_A, json={"note": "S3"})
        mapping[mid] = {"tipo": m.type, "estado": v["status"], "hallazgosPorTipo": v["verification"]["findingsByType"],
                        "aprobacion": a, "mensaje": ab.get("message") if isinstance(ab, dict) else ab}
    after_alfa = received("alfa")
    c = {
        "D1 y D4 síncronos: 422 KIT_BLOCKED, estado FAILED": all(sync[d]["http"] == 422 and sync[d]["codigo"] == "KIT_BLOCKED"
                                                                and sync[d]["estado"] == "FAILED" for d in ("D1", "D4")),
        "Hallazgos con regla, tipo, fuente normativa y huella": all(
            x["ruleId"] and x["type"] and x["source"] and x["sourceSha256"] for d in ("D1", "D4") for x in sync[d]["hallazgos"])
            and all(sync[d]["hallazgos"] for d in ("D1", "D4")),
        "D1 y D4 detectados con su tipo": any(x["type"] == "D1" for x in sync["D1"]["hallazgos"])
            and any(x["type"] == "D4" for x in sync["D4"]["hallazgos"]),
        "D2 y D3 síncronos: 400 en la entrada, sin registrar": all(sync[d]["http"] == 400 and sync[d]["estado"] is None for d in ("D2", "D3")),
        "Lote: D1 y D4 FAILED por el kit": batch["D1"] == "FAILED" and batch["D4"] == "FAILED",
        "Lote: D2 y D3 rechazados en la ingesta (DLQ de entradas)": up.get("rejected") == 2 and batch["D2"] is None and batch["D3"] is None,
        "D5 y D6 (mapeo): versión BLOQUEADA y aprobación 409": all(x["estado"] == "BLOCKED" and x["aprobacion"] == 409 for x in mapping.values()),
        "Ningún documento defectuoso llegó al proveedor": after_alfa == before_alfa,
        "Balance de conservación": st["conservation"]["balanced"],
    }
    intentional = [_key(i) for i in batch_inv] + [_key(i) for i in generate("defects", "--seed", "303", "--offset", "303000")]
    return write("S3", {"escenario": "S3", "descripcion": "Defectos D1 a D6 bloqueados antes del envío, con regla y norma",
                        "clavesDefectuosas": intentional,
                        "sincrono": sync, "lote": {"ingesta": up, "estados": batch}, "mapeo": mapping,
                        "recibidosPorAlfa": {"antes": before_alfa, "despues": after_alfa}, "comprobaciones": c})


def _key(inv: dict) -> str:
    return f"{inv['country']}:{inv['supplier']['taxId']}:{inv['documentType']}:{inv['documentNumber']}".upper()


# --- S4: rechazo del proveedor, DLQ, triaje, aprobación y reproceso ---------------------------------------------
def s4() -> dict:
    inv = generate("generate", "--country", "CO", "--count", "1", "--seed", "404", "--offset", "404000")[0]
    key = _key(inv)
    fault("alfa", "reject-catalog", 1)
    s, b = emit(inv)
    quiet()
    entry = next((e for e in dlq_open() if e["invoiceKey"] == key), None)
    triage = [t for t in triage_agent.run() if t["invoiceKey"] == key]
    agent_try = api("POST", f"/api/dlq/{entry['id']}/reprocess", AGENT, json={})[0] if entry else None
    op = api("POST", f"/api/dlq/{entry['id']}/reprocess", OPERATOR,
             json={"comment": "Triaje revisado: el proveedor corrigió su catálogo"}) if entry else (None, None)
    st = quiet()
    final = status_of(key)
    c = {
        "Rechazo del proveedor: 422 PROVIDER_REJECTED (CAT-01)": s == 422 and b.get("reasonCode") == "PROVIDER_REJECTED",
        "El comprobante queda en la DLQ": entry is not None,
        "Triaje: RECHAZO_CATALOGO_PROVEEDOR con recomendación": bool(triage) and triage[0]["classification"] == "RECHAZO_CATALOGO_PROVEEDOR",
        "El agente no puede reprocesar (403)": agent_try == 403,
        "La operadora aprueba el reproceso (200)": op[0] == 200,
        "Reproceso exitoso: AUTHORIZED": final == "AUTHORIZED",
        "Balance de conservación": st["conservation"]["balanced"],
    }
    return write("S4", {"escenario": "S4", "descripcion": "Rechazo del proveedor, DLQ, triaje del agente, aprobación y reproceso",
                        "comprobante": key, "emision": {"http": s, "codigo": b.get("reasonCode"), "motivo": b.get("reason")},
                        "dlq": entry and {k: entry[k] for k in ("id", "stage", "category", "lastError")},
                        "triaje": triage, "reproceso": {"agente": agent_try, "operadora": op[0], "respuesta": op[1]},
                        "estadoFinal": final, "comprobaciones": c})


# --- S5: incorporación de XX con el proveedor Gamma ---------------------------------------------------------
def s5() -> dict:
    invs = generate("generate", "--country", "XX", "--count", "4", "--seed", "505", "--offset", "505000")
    _, versions = api("GET", "/api/mappings/gamma/XX/versions", OPERATOR)
    had_active = any(v["status"] == "ACTIVE" for v in versions)
    before = None
    if not had_active:
        s, b = emit(invs[0])
        quiet()
        before = {"http": s, "codigo": b.get("reasonCode"), "estado": status_of(_key(invs[0]))}
    cycle = run_cycle("gamma", "XX")
    v = cycle["registry"]["version"]
    base = f"/api/mappings/gamma/XX/versions/{v}"
    agent_approve = api("POST", base + "/approve", AGENT, json={})[0]
    with review_api():
        d = api("POST", f"/api/proposals/{cycle['proposalId']}/decision", REV_A, url=REVIEW_URL,
                json={"decision": "APPROVE", "comment": "Revisado contra la especificación Gamma v1"})
    agent_activate = api("POST", base + "/activate", AGENT, json={})[0]
    a = api("POST", base + "/activate", REV_B, json={"note": "Activación S5"})
    time.sleep(0.5)
    after = []
    for inv in invs[1:]:
        s, b = emit(inv)
        after.append({"http": s, "estado": b.get("status"), "proveedor": b.get("provider"), "mapeo": b.get("mappingVersion"),
                      "autorizacion": b.get("authorizationNumber")})
    reprocessed = None
    if before:
        e = next((x for x in dlq_open() if x["invoiceKey"] == _key(invs[0])), None)
        if e:
            reprocessed = api("POST", f"/api/dlq/{e['id']}/reprocess", OPERATOR, json={"comment": "Mapeo de Gamma activado"})[0]
    st = quiet()
    c = {
        "Sin mapeo: el comprobante de XX no sale y queda en DLQ": before is None or (before["codigo"] == "NO_MAPPING" and before["estado"] == "DLQ"),
        "El agente propone desde la especificación y el kit la deja pasar": cycle["registry"]["status"] == "CANDIDATE",
        "Solicitud de cambio abierta en el repositorio de juguete (sin merge)": cycle["pullRequest"].startswith("PR-"),
        "El agente no puede aprobar (403)": agent_approve == 403,
        "reviewer-a aprueba con su token": d[0] == 200 and d[1].get("reviewer") == REV_A,
        "El agente no puede activar (403)": agent_activate == 403,
        "reviewer-b activa": a[0] == 200 and a[1].get("activatedBy") == REV_B and a[1].get("approvedBy") == REV_A,
        "Comprobantes de XX autorizados por Gamma con el mapeo nuevo": all(x["estado"] == "AUTHORIZED" and x["proveedor"] == "gamma"
                                                                           and x["mapeo"] == v for x in after),
        "El comprobante en DLQ se reprocesa y termina autorizado": before is None or (reprocessed == 200 and status_of(_key(invs[0])) == "AUTHORIZED"),
        "Balance de conservación": st["conservation"]["balanced"],
    }
    return write("S5", {"escenario": "S5", "descripcion": "Incorporación del país sintético XX con el proveedor Gamma",
                        "antesDelMapeo": before, "ciclo": cycle, "decision": {"http": d[0], "revisor": d[1].get("reviewer")},
                        "activacion": {k: a[1].get(k) for k in ("version", "status", "approvedBy", "activatedBy")},
                        "despues": after, "comprobaciones": c})


# --- S6: idempotencia --------------------------------------------------------------------------------------
def s6() -> dict:
    inv = generate("generate", "--country", "CO", "--count", "1", "--seed", "606", "--offset", "606000")[0]
    r0 = stats()["conservation"]["registered"]
    a = emit(inv)
    b = emit(inv)
    lote = generate("generate", "--mix", "CO:60,PE:40", "--seed", "616", "--offset", "616000")
    u1 = upload(lote, "S6-lote.jsonl")
    quiet(min_registered=r0 + 101)
    u2 = upload(lote, "S6-lote-reenvio.jsonl")
    fault("alfa", "duplicate", 1)
    dup_lote = generate("generate", "--country", "CO", "--count", "20", "--seed", "626", "--offset", "626000")
    resp0 = api("GET", "/api/responses/stats", OPERATOR)[1]
    u3 = upload(dup_lote, "S6-respuesta-duplicada.jsonl")
    time.sleep(3)
    st = quiet(min_registered=r0 + 121)
    resp1 = api("GET", "/api/responses/stats", OPERATOR)[1]
    fault("alfa", None)
    keys = [_key(i) for i in lote + dup_lote]
    states = [status_of(k) for k in keys]
    c = {
        "Reenvío síncrono: misma autorización y duplicate=true": a[0] == 200 and b[0] == 200 and b[1].get("duplicate") is True
                                                                 and a[1]["authorizationNumber"] == b[1]["authorizationNumber"],
        "Reenvío del lote: 0 aceptados y 100 duplicados": u2.get("accepted") == 0 and u2.get("duplicates") == 100,
        "Respuesta duplicada del proveedor ignorada": (resp1.get("duplicates", 0) - resp0.get("duplicates", 0)) >= 20,
        "Registrados exactamente 121 nuevos (cero duplicados)": st["conservation"]["registered"] - r0 == 121,
        "Todos autorizados una sola vez": all(x == "AUTHORIZED" for x in states),
        "Balance de conservación": st["conservation"]["balanced"],
    }
    return write("S6", {"escenario": "S6", "descripcion": "Idempotencia ante envío duplicado",
                        "sincrono": {"primera": a[1].get("authorizationNumber"), "segunda": b[1].get("authorizationNumber"),
                                     "duplicate": b[1].get("duplicate")},
                        "lote": {"primera": u1, "reenvio": u2}, "respuestaDuplicada": {"ingesta": u3, "antes": resp0, "despues": resp1},
                        "registradosNuevos": st["conservation"]["registered"] - r0, "comprobaciones": c})


# --- S7: proveedor caído --------------------------------------------------------------------------------------
def s7() -> dict:
    fault("alfa", "unavailable", -1)
    lote = generate("generate", "--mix", "CO:50,PE:30", "--seed", "707", "--offset", "707000")
    st0 = stats()
    t0 = time.time()
    up = upload(lote, "S7-lote.jsonl")
    during = quiet(min_registered=st0["conservation"]["registered"] + 80)
    seconds = round(time.time() - t0, 1)
    co = [_key(i) for i in lote if i["country"] == "CO"]
    pe = [_key(i) for i in lote if i["country"] == "PE"]
    co_states = {status_of(k) for k in co}
    pe_states = {status_of(k) for k in pe}
    fault("alfa", None)
    triage = [t for t in triage_agent.run() if t["invoiceKey"] in set(co)]
    entries = [e for e in dlq_open() if e["invoiceKey"] in set(co)]
    codes = [api("POST", f"/api/dlq/{e['id']}/reprocess", OPERATOR, json={"comment": "Proveedor Alfa recuperado"})[0] for e in entries]
    final = quiet()
    c = {
        "Con Alfa caído, los 50 de CO quedan en DLQ (recuperable)": co_states == {"DLQ"},
        "Los 30 de PE (Beta) se autorizan: el fallo no se propaga": pe_states == {"AUTHORIZED"},
        "Sin pérdidas durante la caída: entradas = salidas + DLQ": during["conservation"]["balanced"] and during["conservation"]["inFlight"] == 0,
        "Triaje: PROVEEDOR_NO_DISPONIBLE": len(triage) == 50 and {t["classification"] for t in triage} == {"PROVEEDOR_NO_DISPONIBLE"},
        "Reproceso de las 50 entradas por la operadora": len(codes) == 50 and set(codes) == {200},
        "Tras la recuperación los 50 terminan autorizados": {status_of(k) for k in co} == {"AUTHORIZED"},
        "Balance de conservación final": final["conservation"]["balanced"],
    }
    return write("S7", {"escenario": "S7", "descripcion": "Proveedor caído: reintentos y DLQ, sin pérdida de comprobantes",
                        "ingesta": up, "segundosHastaCuarentena": seconds, "durante": during["conservation"],
                        "estados": {"CO": sorted(co_states), "PE": sorted(pe_states)},
                        "triaje": triage[:3], "reprocesos": len(codes), "final": final["conservation"], "comprobaciones": c})


# --- S8: reversión de un mapeo --------------------------------------------------------------------------------
def s8() -> dict:
    invs = generate("generate", "--country", "PE", "--count", "2", "--seed", "808", "--offset", "808000")
    _, versions = api("GET", "/api/mappings/beta/PE/versions", OPERATOR)
    previous = next(v["version"] for v in versions if v["status"] == "ACTIVE")
    cycle = run_cycle("beta", "PE")
    v = cycle["registry"]["version"]
    with review_api():
        d = api("POST", f"/api/proposals/{cycle['proposalId']}/decision", REV_A, url=REVIEW_URL,
                json={"decision": "APPROVE", "comment": "Reglas extraídas del legado con archivo y línea"})
    act = api("POST", f"/api/mappings/beta/PE/versions/{v}/activate", REV_B, json={"note": "S8: versión del legado"})
    time.sleep(0.5)
    s1, b1 = emit(invs[0])
    agent_rb = api("POST", "/api/mappings/beta/PE/rollback", AGENT, json={})[0]
    rb = api("POST", "/api/mappings/beta/PE/rollback", REV_A, json={"note": "S8: reversión"})
    time.sleep(0.5)
    s2, b2 = emit(invs[1])
    audit = api("GET", "/api/audit?limit=50", OPERATOR)[1]
    rollback_audit = next((x for x in audit if x["action"] == "MAPPING_ROLLBACK"), None)
    st = quiet()
    c = {
        "El agente de legado propone una versión que pasa el kit": cycle["registry"]["status"] == "CANDIDATE",
        "Aprobada por reviewer-a y activada por reviewer-b": d[0] == 200 and act[0] == 200 and act[1].get("activatedBy") == REV_B,
        "Comprobante con la versión nueva": s1 == 200 and b1.get("mappingVersion") == v and b1.get("status") == "AUTHORIZED",
        "El agente no puede revertir (403)": agent_rb == 403,
        "reviewer-a revierte a la versión anterior": rb[0] == 200 and rb[1].get("version") == previous and rb[1].get("status") == "ACTIVE",
        "Comprobante posterior con la versión anterior": s2 == 200 and b2.get("mappingVersion") == previous and b2.get("status") == "AUTHORIZED",
        "Reversión auditada con la identidad del token": rollback_audit is not None and rollback_audit["actor"] == REV_A,
        "Balance de conservación": st["conservation"]["balanced"],
    }
    return write("S8", {"escenario": "S8", "descripcion": "Reversión de un mapeo", "versionAnterior": previous, "versionNueva": v,
                        "ciclo": cycle, "conVersionNueva": {"http": s1, "mapeo": b1.get("mappingVersion")},
                        "reversion": {k: rb[1].get(k) for k in ("version", "status", "activatedBy")} if rb[0] == 200 else rb,
                        "conVersionAnterior": {"http": s2, "mapeo": b2.get("mappingVersion")},
                        "auditoria": rollback_audit, "comprobaciones": c})


# --- Banco de 22 defectos por el sistema real ------------------------------------------------------------
def _bank_base(fmt: str) -> tuple[str, str, dict]:
    if fmt == "xml":
        return "alfa", "CO", load_mapping(POC_HOME / "agentic-plane/golden/mapping-alfa-v2.json")
    return "beta", "PE", load_mapping(POC_HOME / "data/mappings/beta-PE-v1.json")


def banco() -> dict:
    rows, fp = [], {}
    for fmt in ("xml", "seg"):
        prov, ctry, base = _bank_base(fmt)
        s, v = api("POST", f"/api/mappings/{prov}/{ctry}/candidates", AGENT,
                   json={"mapping": base, "source": "banco-linea-base", "proposalRef": f"base-{fmt}"})
        fp[f"{prov}/{ctry}"] = {"bloqueada": not v["gatePassed"], "verificacion": v["verification"]}
        api("POST", f"/api/mappings/{prov}/{ctry}/versions/{v['version']}/reject", REV_A, json={"note": "Línea base del banco"})
    for m in MUTANTS:
        prov, ctry, base = _bank_base(m.format)
        t0 = time.time()
        s, v = api("POST", f"/api/mappings/{prov}/{ctry}/candidates", AGENT,
                   json={"mapping": m.apply(base), "source": "banco-defectos", "proposalRef": m.id})
        secs = round(time.time() - t0, 2)
        ver = v["verification"]
        if v["gatePassed"]:
            approve = None
            api("POST", f"/api/mappings/{prov}/{ctry}/versions/{v['version']}/reject", REV_A, json={"note": "Mutante no detectado"})
        else:
            approve = api("POST", f"/api/mappings/{prov}/{ctry}/versions/{v['version']}/approve", REV_A, json={"note": "banco"})[0]
        rows.append({"id": m.id, "tipo": m.type, "descripcion": m.description, "destino": f"{prov}/{ctry}",
                     "ejecucion": ver["runtimeFailed"] > 0, "ci": ver["ciFailed"] > 0, "bloqueada": not v["gatePassed"],
                     "docsEjecucionConHallazgos": f"{ver['runtimeFailed']}/{ver['runtimeDocuments']}",
                     "ejemplosCiConHallazgos": f"{ver['ciFailed']}/{ver['ciDocuments']}",
                     "tiposDetectados": ver["findingsByType"], "aprobacion": approve, "segundos": secs})
    n = len(rows)
    by_type: dict[str, dict] = {}
    for r in rows:
        t = by_type.setdefault(r["tipo"], {"mutantes": 0, "ejecucion": 0, "sistema": 0})
        t["mutantes"] += 1
        t["ejecucion"] += r["ejecucion"]
        t["sistema"] += r["bloqueada"]
    total = {"mutantes": n, "ejecucion": sum(r["ejecucion"] for r in rows), "sistema": sum(r["bloqueada"] for r in rows)}
    doc = {"soloEsquema": "23 %", "kitEnEjecucion": "86 %", "kitMasCi": "100 %", "falsosPositivos": 0}
    c = {
        "Los 22 mutantes se evalúan por el registro de mapeos": n == 22,
        "Kit + CI en el sistema: 100 % bloqueados": total["sistema"] == n,
        "Ningún mutante bloqueado se puede aprobar (409)": all(r["aprobacion"] == 409 for r in rows if r["bloqueada"]),
        "Cero falsos positivos (líneas base aceptadas)": not any(x["bloqueada"] for x in fp.values()),
    }
    return write("BANCO", {"escenario": "BANCO", "descripcion": "Banco de 22 defectos por el sistema real",
                           "documento": doc, "porTipo": by_type, "total": total,
                           "ejecucionPct": round(100 * total["ejecucion"] / n), "sistemaPct": round(100 * total["sistema"] / n),
                           "lineasBase": fp, "mutantes": rows, "comprobaciones": c})


# --- Rendimiento: latencia de verificación, latencia síncrona y lote ----------------------------------------
def rendimiento(sync_n: int = 200, verify_n: int = 400, batch_co: int = 6000, batch_pe: int = 4000) -> dict:
    invs = generate("generate", "--country", "CO", "--count", str(sync_n), "--seed", "909", "--offset", "909000")
    sync_ms = []
    for inv in invs:
        t0 = time.perf_counter()
        s, _ = emit(inv)
        sync_ms.append((time.perf_counter() - t0) * 1000)
        assert s == 200, s
    docs = []
    for st_, prof in (("alfa", "alfa"), ("beta", "beta-pe")):
        page = api("GET", f"/api/invoices?status=AUTHORIZED&provider={st_}&limit={verify_n // 2}", OPERATOR)[1]
        for x in page:
            d = api("GET", "/api/invoices/" + x["invoiceKey"], OPERATOR)[1]
            if d.get("transformed"):
                docs.append((prof, d["transformed"]))
    http_ms, kit_ms, kit_passed = [], [], 0
    for prof, content in docs:
        t0 = time.perf_counter()
        s, r = api("POST", f"/api/kit/verify?profile={prof}", OPERATOR, content=content.encode("utf-8"),
                   headers={"Content-Type": "text/plain; charset=utf-8"})
        http_ms.append((time.perf_counter() - t0) * 1000)
        kit_ms.append(r["durationMicros"] / 1000)
        kit_passed += bool(r.get("passed"))
    lote = generate("generate", "--mix", f"CO:{batch_co},PE:{batch_pe}", "--seed", "919", "--offset", "919000")
    r0 = stats()["conservation"]["registered"]
    t0 = time.time()
    up = upload(lote, "rendimiento-lote.jsonl")
    accepted_s = time.time() - t0
    st = quiet(timeout=1800, min_registered=r0 + batch_co + batch_pe)
    total_s = time.time() - t0
    # Estado final de cada comprobante del lote (fuera del tiempo medido).
    lote_states = {}
    for i in lote:
        x = status_of(_key(i))
        lote_states[x] = lote_states.get(x, 0) + 1
    n = batch_co + batch_pe
    per_s = n / total_s
    res = {
        "verificacionKit": {"documentos": len(kit_ms), "aprobados": kit_passed, "p50ms": pct(kit_ms, .5), "p95ms": pct(kit_ms, .95),
                            "http": {"p50ms": pct(http_ms, .5), "p95ms": pct(http_ms, .95)}},
        "emisionSincrona": {"comprobantes": sync_n, "autorizados": sync_n, "p50ms": pct(sync_ms, .5), "p95ms": pct(sync_ms, .95),
                            "maxMs": round(max(sync_ms), 1), "media": round(statistics.mean(sync_ms), 1)},
        "lote": {"comprobantes": n, "segundosAceptacion": round(accepted_s, 1), "segundosTotales": round(total_s, 1),
                 "porSegundo": round(per_s, 1), "proyeccionPorDia": int(per_s * 86400), "ingesta": up, "estados": lote_states,
                 "conservacion": st["conservation"]},
        "umbrales": {"emisionSincronaP95ms": 3000, "diaPico": 200000},
    }
    c = {
        "p95 de la emisión síncrona < 3 s (incluye el simulador)": res["emisionSincrona"]["p95ms"] < 3000,
        "Lote completo sin pérdidas": st["conservation"]["balanced"] and st["conservation"]["inFlight"] == 0
                                       and st["conservation"]["registered"] - r0 == n,
        "Capacidad proyectada >= 200.000 comprobantes por día": res["lote"]["proyeccionPorDia"] >= 200000,
    }
    return write("RENDIMIENTO", {"escenario": "RENDIMIENTO", "descripcion": "Latencia de verificación y emisión, y rendimiento del lote",
                                 **res, "comprobaciones": c})


# --- Fase 6b: falsos positivos con n explícito ------------------------------------------------------------
def _load(sid: str) -> dict:
    return json.loads((RESULTS / f"{sid}.json").read_text(encoding="utf-8-sig"))  # los de PowerShell llevan BOM


def falsos_positivos() -> dict:
    """Comprobantes correctos que pasaron por el kit y cuántos bloqueó por error, con la fuente de cada conteo.

    Se cuentan comprobantes únicos (los reenvíos duplicados no suman). Un falso positivo es un comprobante
    correcto que termina FAILED con KIT_BLOCKED; los únicos bloqueos esperados son los defectos intencionales de S3.
    """
    s1, s2, s6, s7, rd, s3 = (_load(x) for x in ("S1", "S2", "S6", "S7", "RENDIMIENTO", "S3"))
    failed = []
    offset = 0
    while True:
        page = api("GET", f"/api/invoices?status=FAILED&limit=500&offset={offset}", OPERATOR)[1]
        failed += page
        if len(page) < 500:
            break
        offset += 500
    kit_blocked = [f["invoiceKey"] for f in failed if (api("GET", "/api/invoices/" + f["invoiceKey"], OPERATOR)[1].get("errorCode") == "KIT_BLOCKED")]
    intentional = set(s3["clavesDefectuosas"])
    false_pos = [k for k in kit_blocked if k not in intentional]
    sources = [
        {"fuente": "S1 (emisión síncrona)", "archivo": "S1.json", "correctos": 1,
         "bloqueadosPorError": 0 if s1["estado"] == "AUTHORIZED" else 1},
        {"fuente": "S2 (lote de 1.000)", "archivo": "S2.json", "correctos": s2["comprobantes"],
         "bloqueadosPorError": 0 if s2["comprobaciones"][f"Autorizados {s2['comprobantes']}"] else None},
        {"fuente": "S6 (idempotencia, sin contar reenvíos)", "archivo": "S6.json", "correctos": s6["registradosNuevos"],
         "bloqueadosPorError": 0 if s6["comprobaciones"]["Todos autorizados una sola vez"] else None},
        {"fuente": "S7 (proveedor caído; reprocesados cuentan una vez)", "archivo": "S7.json", "correctos": 80,
         "bloqueadosPorError": 0 if s7["comprobaciones"]["Tras la recuperación los 50 terminan autorizados"]
         and s7["estados"]["PE"] == ["AUTHORIZED"] else None},
        {"fuente": "Medición de latencia síncrona", "archivo": "RENDIMIENTO.json", "correctos": rd["emisionSincrona"]["comprobantes"],
         "bloqueadosPorError": rd["emisionSincrona"]["comprobantes"] - rd["emisionSincrona"]["autorizados"]},
        {"fuente": "Medición del lote de 10.000", "archivo": "RENDIMIENTO.json", "correctos": rd["lote"]["comprobantes"],
         "bloqueadosPorError": rd["lote"]["comprobantes"] - rd["lote"]["estados"].get("AUTHORIZED", 0)},
    ]
    n = sum(x["correctos"] for x in sources)
    fp = sum(x["bloqueadosPorError"] or 0 for x in sources)
    direct = {"fuente": "Verificación directa del kit en la medición de latencia (documentos ya autorizados; no suman comprobantes nuevos)",
              "archivo": "RENDIMIENTO.json", "verificaciones": rd["verificacionKit"]["documentos"],
              "rechazadasPorError": rd["verificacionKit"]["documentos"] - rd["verificacionKit"]["aprobados"]}
    result = {"escenario": "FALSOS_POSITIVOS", "descripcion": "Falsos positivos del kit con n explícito",
              "fuentes": sources, "n": n, "falsosPositivos": fp,
              "bloqueosKitEnElRegistro": len(kit_blocked), "bloqueosIntencionalesS3": len(intentional & set(kit_blocked)),
              "falsosPositivosEnElRegistro": false_pos,
              "cotaSuperior95ReglaDeTres": round(3 / n, 6), "cotaSuperior95Exacta": round(1 - 0.05 ** (1 / n), 6),
              "verificacionesDirectas": direct,
              "comprobaciones": {"Todas las fuentes tienen conteo": all(x["bloqueadosPorError"] is not None for x in sources),
                                 f"0 falsos positivos en {n} comprobantes correctos": fp == 0,
                                 "Los únicos bloqueos del kit son los defectos intencionales de S3": not false_pos,
                                 "Verificaciones directas sin rechazos": direct["rechazadasPorError"] == 0}}
    return write("FALSOS_POSITIVOS", result, "falsos_positivos.json")


# --- Fase 6b: banco ampliado ----------------------------------------------------------------------------------
def wilson(k: int, n: int, z: float = 1.959964) -> list[float]:
    if n == 0:
        return [0.0, 0.0]
    p = k / n
    d = 1 + z * z / n
    c = (p + z * z / (2 * n)) / d
    h = z * ((p * (1 - p) / n + z * z / (4 * n * n)) ** 0.5) / d
    return [round(max(0.0, c - h), 4), round(min(1.0, c + h), 4)]


def banco_ampliado() -> dict:
    from . import mutation_bank
    bank = mutation_bank.build()
    rows = []
    for m in bank["mutants"]:
        prov, ctry, mapping = mutation_bank.mapping_for(m)
        s, v = api("POST", f"/api/mappings/{prov}/{ctry}/candidates", AGENT,
                   json={"mapping": mapping, "source": "banco-ampliado", "proposalRef": m["id"]})
        ver = v["verification"]
        if v["gatePassed"]:
            approve = None
            api("POST", f"/api/mappings/{prov}/{ctry}/versions/{v['version']}/reject", REV_A, json={"note": "Mutante no detectado"})
        else:
            approve = api("POST", f"/api/mappings/{prov}/{ctry}/versions/{v['version']}/approve", REV_A, json={"note": "banco ampliado"})[0]
        rows.append({k: m[k] for k in ("id", "type", "format", "operator", "params", "description", "groundTruth", "equivalent")}
                    | {"destino": f"{prov}/{ctry}", "ejecucion": ver["runtimeFailed"] > 0, "ci": ver["ciFailed"] > 0,
                       "sistema": not v["gatePassed"], "docsEjecucion": f"{ver['runtimeFailed']}/{ver['runtimeDocuments']}",
                       "ejemplosCi": f"{ver['ciFailed']}/{ver['ciDocuments']}", "tiposDetectados": ver["findingsByType"],
                       "aprobacion": approve})
    by_type = {}
    for t in ("D1", "D2", "D3", "D4", "D5", "D6"):
        r = [x for x in rows if x["type"] == t]
        n, ke, ks = len(r), sum(x["ejecucion"] for x in r), sum(x["sistema"] for x in r)
        by_type[t] = {"mutantes": n, "ejecucion": ke, "ejecucionPct": round(100 * ke / n, 1) if n else None, "ejecucionWilson95": wilson(ke, n),
                      "conEjemplosValidados": ks, "conEjemplosValidadosPct": round(100 * ks / n, 1) if n else None,
                      "conEjemplosValidadosWilson95": wilson(ks, n), **bank["summary"][t],
                      "noDetectados": [x["id"] for x in r if not x["sistema"]],
                      "escapanEnEjecucion": [x["id"] for x in r if not x["ejecucion"]]}
    n = len(rows)
    ke, ks = sum(x["ejecucion"] for x in rows), sum(x["sistema"] for x in rows)
    kit_pom = (POC_HOME / "verification-kit" / "pom.xml").read_text(encoding="utf-8")
    import re as _re
    kit_version = _re.search(r"<artifactId>verification-kit</artifactId>\s*<version>([^<]+)</version>", kit_pom).group(1)
    from .specs import spec as _spec
    result = {
        "escenario": "BANCO_AMPLIADO", "descripcion": "Banco ampliado generado con los operadores de D1 a D6, por el sistema real",
        "bancoOriginal": "BANCO.json (22 mutantes del documento; no se modifica)",
        "semilla": bank["seed"], "maximoPorTipo": bank["perType"], "huellaDelConjunto": bank["sha256"],
        "generador": "agentic-plane/tfg_agents/mutation_bank.py", "ejecutor": "agentic-plane/tfg_agents/scenarios.py (banco_ampliado)",
        "script": "scripts/escenarios/banco_ampliado.ps1", "versionKit": kit_version,
        "especificaciones": {n_: _spec(n_).sha256 for n_ in ("perfil-alfa-v2.1", "formato-beta-pe-v1")},
        "criterioEquivalencia": ["E1: mapeo mutado idéntico a la línea base",
                                 "E2: cambio limitado a claves o fuentes fuera del dominio del país de destino",
                                 "E3: literal mutado igual al valor que la línea base produce en todo el dominio",
                                 "Un mutante no detectado no se reclasifica como equivalente"],
        "porTipo": by_type,
        "total": {"mutantes": n, "ejecucion": ke, "ejecucionPct": round(100 * ke / n, 1), "ejecucionWilson95": wilson(ke, n),
                  "conEjemplosValidados": ks, "conEjemplosValidadosPct": round(100 * ks / n, 1), "conEjemplosValidadosWilson95": wilson(ks, n),
                  "equivalentesExcluidos": sum(sum(v["equivalentesExcluidos"].values()) for v in bank["summary"].values())},
        "mutantes": rows,
        "comprobaciones": {"Cada mutante se verificó por el registro de mapeos": len(rows) == len(bank["mutants"]),
                           "Ningún mutante bloqueado se puede aprobar (409)": all(x["aprobacion"] == 409 for x in rows if x["sistema"])},
    }
    return write("BANCO_AMPLIADO", result, "banco_ampliado.json")


SCENARIOS = {"s3": s3, "s4": s4, "s5": s5, "s6": s6, "s7": s7, "s8": s8, "banco": banco, "rendimiento": rendimiento,
             "falsos_positivos": falsos_positivos, "banco_ampliado": banco_ampliado}


def main(argv: list[str]) -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    T.update(_tokens())
    names = list(SCENARIOS) if argv == ["todos"] else argv
    ok = True
    for n in names:
        ok &= SCENARIOS[n.lower()]()["aprobado"]
    return 0 if ok else 1


if __name__ == "__main__":
    if len(sys.argv) < 2 or any(a.lower() not in SCENARIOS and a != "todos" for a in sys.argv[1:]):
        print("Uso: python -m tfg_agents.scenarios <" + "|".join(SCENARIOS) + "|todos>", file=sys.stderr)
        sys.exit(64)
    sys.exit(main(sys.argv[1:]))
