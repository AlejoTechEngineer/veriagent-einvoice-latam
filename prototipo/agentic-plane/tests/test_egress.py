"""Lista blanca de salida del plano agéntico (T2 a): solo el kit (la plataforma que lo sirve) y el proveedor del
modelo configurado. Cualquier otro destino se rechaza antes de abrir la conexión."""
import httpx
import pytest

from tfg_agents import egress
from tfg_agents.platform_client import PlatformClient

PLATFORM = "http://127.0.0.1:8080"


@pytest.fixture(autouse=True)
def config(monkeypatch):
    monkeypatch.setenv("VERIAGENT_URL", PLATFORM)
    for var in ("TFG_KIT_URL", "ANTHROPIC_API_KEY", "ANTHROPIC_BASE_URL"):
        monkeypatch.delenv(var, raising=False)


def recording_client():
    seen = []

    def handler(request):
        seen.append(str(request.url))
        return httpx.Response(200, json={"ok": True})

    return egress.client(transport=httpx.MockTransport(handler)), seen


def test_kit_destination_allowed():
    c, seen = recording_client()
    assert c.post(PLATFORM + "/api/kit/verify").status_code == 200
    assert seen == [PLATFORM + "/api/kit/verify"]


def test_standalone_kit_allowed_only_when_configured(monkeypatch):
    c, seen = recording_client()
    with pytest.raises(egress.EgressDenied):
        c.post("http://127.0.0.1:8085/verify")
    monkeypatch.setenv("TFG_KIT_URL", "http://127.0.0.1:8085")
    assert c.post("http://127.0.0.1:8085/verify").status_code == 200
    assert seen == ["http://127.0.0.1:8085/verify"]


@pytest.mark.parametrize("url", [
    "https://exfiltracion.example/recoger",
    "http://127.0.0.1:9999/api/kit/verify",      # mismo host, otro puerto
    "https://127.0.0.1:8080/api/kit/verify",     # mismo host y puerto, otro esquema
    "http://localhost:8080/api/kit/verify",      # otro nombre para el mismo equipo: no está en la lista
    "http://169.254.169.254/latest/meta-data/",  # metadatos de la nube
    "file:///etc/passwd",
])
def test_other_destinations_rejected_before_connecting(url):
    c, seen = recording_client()
    with pytest.raises(egress.EgressDenied):
        c.get(url)
    with pytest.raises(egress.EgressDenied):
        egress.request("GET", url)
    assert seen == []


def test_llm_provider_allowed_only_when_model_configured(monkeypatch):
    c, seen = recording_client()
    with pytest.raises(egress.EgressDenied):
        c.post("https://api.anthropic.com/v1/messages")
    monkeypatch.setenv("ANTHROPIC_API_KEY", "clave-de-prueba")
    assert c.post("https://api.anthropic.com/v1/messages").status_code == 200
    with pytest.raises(egress.EgressDenied):
        c.post("https://otro-proveedor.example/v1/messages")
    monkeypatch.setenv("ANTHROPIC_BASE_URL", "https://pasarela-modelo.example")
    with pytest.raises(egress.EgressDenied):
        c.post("https://api.anthropic.com/v1/messages")
    assert c.post("https://pasarela-modelo.example/v1/messages").status_code == 200
    assert len(seen) == 2


def test_redirect_outside_allowlist_rejected():
    seen = []

    def handler(request):
        seen.append(str(request.url))
        return httpx.Response(302, headers={"Location": "https://exfiltracion.example/"})

    c = egress.client(transport=httpx.MockTransport(handler), follow_redirects=True)
    with pytest.raises(egress.EgressDenied):
        c.get(PLATFORM + "/api/mappings")
    assert seen == [PLATFORM + "/api/mappings"]


def test_platform_client_rejects_other_url():
    with pytest.raises(egress.EgressDenied):
        PlatformClient(token="t", url="https://exfiltracion.example").mapping_versions("alfa", "CO")


def test_llm_sdk_client_goes_through_allowlist(monkeypatch):
    anthropic = pytest.importorskip("anthropic")
    monkeypatch.setenv("ANTHROPIC_API_KEY", "clave-de-prueba")
    sdk = anthropic.Anthropic(api_key="clave-de-prueba", base_url="https://exfiltracion.example",
                              http_client=egress.sdk_client(), max_retries=0)
    with pytest.raises(anthropic.APIConnectionError) as err:
        sdk.messages.create(model="modelo", max_tokens=1, messages=[{"role": "user", "content": "hola"}])
    assert isinstance(err.value.__cause__, egress.EgressDenied)


def test_llm_backends_build_sdk_with_allowlist(monkeypatch):
    anthropic = pytest.importorskip("anthropic")
    from tfg_agents.mapping_agent import AnthropicBackend
    from tfg_agents.spec_mapping_agent import SpecLlmBackend
    monkeypatch.setenv("ANTHROPIC_API_KEY", "clave-de-prueba")
    monkeypatch.setenv("ANTHROPIC_BASE_URL", "https://exfiltracion.example")
    monkeypatch.setattr(egress, "allowed_origins", lambda: {egress.origin(PLATFORM)})
    for backend in (AnthropicBackend(), SpecLlmBackend("gamma", "XX")):
        with pytest.raises(anthropic.APIConnectionError) as err:
            backend.client.with_options(max_retries=0).messages.create(
                model="modelo", max_tokens=1, messages=[{"role": "user", "content": "hola"}])
        assert isinstance(err.value.__cause__, egress.EgressDenied)
