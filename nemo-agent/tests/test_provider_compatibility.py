"""Provider request options must match the structured tool-calling protocol."""
import pytest
from nemo_agent.analysis_graph import create_model
from nemo_agent.config import Settings


@pytest.mark.parametrize("model_id,base_url,expected", [
    ("qwen3.8-flash", "https://workspace.cn-beijing.maas.aliyuncs.com/compatible-mode/v1", {"enable_thinking": False}),
    ("qwen3.8-flash", "https://dashscope.aliyuncs.com/compatible-mode/v1", {"enable_thinking": False}),
    ("qwen3.8-flash", "https://dashscope-intl.aliyuncs.com/compatible-mode/v1", {"enable_thinking": False}),
    ("deepseek-flash", "https://api.deepseek.com", {"thinking": {"type": "disabled"}}),
    ("other-model", "https://workspace.cn-beijing.maas.aliyuncs.com/compatible-mode/v1", None),
    ("qwen3.8-flash", "https://api.example.com/v1", None),
    ("qwen3.8-flash", "https://maas.aliyuncs.com.attacker.invalid/v1", None),
    ("qwen3.8-flash", "https://api.example.com/v1?host=maas.aliyuncs.com", None),
])
def test_structured_provider_options(model_id, base_url, expected):
    model = create_model(Settings(environment="test", provider="openai", model_id=model_id,
                                  api_key="test-only", base_url=base_url))
    assert model.extra_body == expected
    assert model.max_retries == 0
    assert model.model_name == model_id
    assert model.temperature == 0
