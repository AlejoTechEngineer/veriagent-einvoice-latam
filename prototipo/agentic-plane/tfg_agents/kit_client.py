"""Cliente del kit de verificación Java: modo lote por CLI (evaluación y CI) y modo HTTP (servicio)."""
from __future__ import annotations

import json
import os
import subprocess
from pathlib import Path

import httpx

from . import POC_HOME

KIT_CLASSES = POC_HOME / "verification-kit" / "build" / "classes"


def kit_command() -> list[str]:
    jar = os.environ.get("TFG_KIT_JAR")
    if jar:
        return ["java", "-jar", jar]
    return ["java", "-cp", str(KIT_CLASSES), "org.tfg.kit.cli.Main"]


def verify_batch(rows: list[tuple[str, str, str, Path, Path | None]], workdir: Path) -> dict[str, dict]:
    """rows: (id, formato, modo, archivo, esperado). Devuelve {id: reporte}."""
    manifest = workdir / "manifest.csv"
    out = workdir / "reports.jsonl"
    with manifest.open("w", encoding="utf-8") as f:
        f.write("id,format,mode,file,expected\n")
        for rid, fmt, mode, path, expected in rows:
            f.write(f"{rid},{fmt},{mode},{path},{expected or ''}\n")
    subprocess.run(kit_command() + ["batch", "--home", str(POC_HOME), str(manifest), str(out)],
                   check=True, capture_output=True, text=True)
    reports = {}
    for line in out.read_text(encoding="utf-8").splitlines():
        r = json.loads(line)
        reports[r["documentId"]] = r
    return reports


def verify_http(content: str, fmt: str = "xml", mode: str = "RUNTIME", base_url: str | None = None) -> dict:
    url = (base_url or os.environ.get("TFG_KIT_URL", "http://localhost:8085")) + "/verify"
    r = httpx.post(url, params={"format": fmt, "mode": mode}, content=content.encode("utf-8"), timeout=10)
    return r.json()
