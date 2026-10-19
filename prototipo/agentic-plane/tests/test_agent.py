import json
from types import SimpleNamespace

from tfg_agents.evaluation import GOLDEN
from tfg_agents.mapping import load_mapping
from tfg_agents.mapping_agent import AnthropicBackend, HeuristicBackend, field_accuracy
from tfg_agents.orchestrator import verify_proposal


def test_heuristic_agent_baseline_accuracy():
    acc = field_accuracy(HeuristicBackend().propose(), load_mapping(GOLDEN / "mapping-alfa-v2.json"))
    assert acc["accuracy"] >= 0.9


def test_kit_blocks_the_heuristic_agent_error():
    v = verify_proposal(HeuristicBackend().propose(), sample=10)
    assert not v["gatePassed"]
    assert "R-D3-02" in v["findingsByRule"]


class FakeClient:
    """Cliente simulado del LLM: devuelve JSON con una entrada válida y dos inválidas."""
    def __init__(self):
        self.messages = self
        self.last = None

    def create(self, **kw):
        self.last = kw
        body = {"entries": [
            {"target": "Invoice/ID", "source": "documentNumber", "transform": "identity", "evidence": {"section": "§2.2"}},
            {"target": "Invoice/Inventado", "source": "documentNumber"},
            {"target": "Invoice/IssueDate", "source": "campoQueNoExiste"},
        ]}
        return SimpleNamespace(content=[SimpleNamespace(type="text", text="```json\n" + json.dumps(body) + "\n```")])


def test_llm_backend_filters_hallucinated_targets_and_sources():
    fake = FakeClient()
    p = AnthropicBackend(client=fake, model="modelo-de-prueba").propose()
    assert [e["target"] for e in p["entries"]] == ["Invoice/ID"]
    assert p["generatedBy"] == "anthropic-llm:modelo-de-prueba"
    assert "sha256" in fake.last["messages"][0]["content"]
