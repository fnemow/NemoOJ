"""Shared teaching utilities. All model network access is explicit --online."""
import getpass
import json
import os
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

from dotenv import load_dotenv
from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.language_models.fake_chat_models import FakeListChatModel
from langchain_core.messages import AIMessage, BaseMessage
from langchain_core.outputs import ChatGeneration, ChatResult
from pydantic import Field

ROOT = Path(__file__).resolve().parent
load_dotenv(ROOT / '.env', override=False)
# Tracing is a separate opt-in because it can transmit prompts to another service.
os.environ['LANGSMITH_TRACING'] = os.getenv('TUTORIAL_TRACE', 'false')


def show(value: Any) -> None:
    print(json.dumps(value, ensure_ascii=False, indent=2, default=str))


def output_dir() -> Path:
    directory = Path(os.getenv('TUTORIAL_OUTPUT_DIR', str(ROOT / 'outputs'))).resolve()
    directory.mkdir(parents=True, exist_ok=True)
    return directory


def online_model():
    from langchain_openai import ChatOpenAI
    model_id = os.getenv('TUTORIAL_MODEL', '').strip()
    base_url = os.getenv('TUTORIAL_BASE_URL', '').strip()
    if not model_id or model_id == 'replace-with-your-enabled-model-id':
        raise ValueError('Set TUTORIAL_MODEL to a model enabled in your provider console.')
    if urlparse(base_url).scheme != 'https':
        raise ValueError('Set TUTORIAL_BASE_URL to your provider HTTPS API URL.')
    key = os.getenv('TUTORIAL_API_KEY') or getpass.getpass('模型 API Key（输入隐藏）: ')
    if not key:
        raise ValueError('An API key is required only for --online.')
    extra = json.loads(os.getenv('TUTORIAL_EXTRA_BODY', '{}'))
    if not isinstance(extra, dict):
        raise ValueError('TUTORIAL_EXTRA_BODY must be a JSON object.')
    return ChatOpenAI(model=model_id, base_url=base_url, api_key=key,
                      temperature=0, max_tokens=1200, timeout=45,
                      max_retries=0, extra_body=extra)


def chat_model(reply: str, online: bool = False):
    return online_model() if online else FakeListChatModel(responses=[reply])


class ScriptedToolModel(BaseChatModel):
    """A fixed response tape, not an intelligent model. Exercises the real tool loop."""
    responses: list[AIMessage]
    index: int = Field(default=0)

    @property
    def _llm_type(self) -> str:
        return 'tutorial-scripted-tool-model'

    def bind_tools(self, tools, **kwargs):
        return self

    def _generate(self, messages: list[BaseMessage], stop=None,
                  run_manager=None, **kwargs) -> ChatResult:
        if self.index >= len(self.responses):
            raise RuntimeError('Scripted responses exhausted: inspect the tool loop.')
        answer = self.responses[self.index]
        self.index += 1
        return ChatResult(generations=[ChatGeneration(message=answer)])
