"""Prueba estática de la política de red del plano agéntico (T2 b). Renderiza la plantilla con un sustituto mínimo de
Helm (solo las expresiones que usa este archivo) y comprueba sus reglas. No sustituye a `helm template` ni a la
validación en un cluster, que no se ejecutaron (sin helm ni cluster)."""
import re
from pathlib import Path

import pytest

yaml = pytest.importorskip("yaml")

CHART = Path(__file__).resolve().parents[2] / "deploy" / "helm" / "tfg-poc"


def render(name: str) -> dict:
    values = yaml.safe_load((CHART / "values.yaml").read_text(encoding="utf-8"))
    text = (CHART / "templates" / name).read_text(encoding="utf-8")

    def lookup(path: str):
        v = values
        for k in path.split("."):
            v = v[k]
        return v

    text = re.sub(r'\{\{- include "tfg.labels" \. \| nindent 4 \}\}', "\n    app.kubernetes.io/part-of: tfg-poc", text)
    text = text.replace("{{ .Release.Name }}", "rel")
    text = re.sub(r"\{\{ \.Values\.([\w.]+) \| quote \}\}", lambda m: f'"{lookup(m.group(1))}"', text)
    text = re.sub(r"\{\{ \.Values\.([\w.]+) \}\}", lambda m: str(lookup(m.group(1))), text)
    assert "{{" not in text, "quedó una expresión de plantilla sin sustituir"
    return yaml.safe_load(text)


def test_agentic_egress_limited_to_kit_platform_proxy_and_dns():
    policy = render("networkpolicy.yaml")
    spec = policy["spec"]
    assert spec["podSelector"] == {"matchLabels": {"app.kubernetes.io/name": "agentic-plane"}}
    assert spec["policyTypes"] == ["Egress"]
    rules = spec["egress"]
    for rule in rules:
        assert rule.get("to"), f"regla de salida sin destino (abierta a cualquier IP): {rule}"
        for peer in rule["to"]:
            assert "ipBlock" not in peer, f"regla por bloque de IP: {peer}"
            assert "podSelector" in peer, f"destino sin selector de pods: {peer}"
    targets = {}
    for rule in rules:
        for peer in rule["to"]:
            name = peer["podSelector"]["matchLabels"].get("app.kubernetes.io/name") or \
                peer["podSelector"]["matchLabels"].get("k8s-app")
            targets[name] = sorted(p["port"] for p in rule["ports"])
    assert targets == {"verification-kit": [8085], "platform": [8080], "egress-proxy": [3128], "kube-dns": [53, 53]}
    ports = [p["port"] for r in rules for p in r["ports"]]
    assert 443 not in ports, "no hay salida HTTPS directa: el modelo se alcanza solo por el proxy"


def test_agentic_deployment_uses_proxy():
    docs = (CHART / "templates" / "agentic.yaml").read_text(encoding="utf-8")
    assert "name: HTTPS_PROXY" in docs and "egress.proxy.service" in docs
    assert "name: NO_PROXY" in docs
