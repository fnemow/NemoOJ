"""Agentic retrieval: search is a tool; the model controls whether to call it."""
import argparse
from langchain.agents import create_agent
from langchain_core.messages import AIMessage
from langchain_core.tools import tool
from common import ScriptedToolModel, online_model, show
from rag_utils import context_text, keyword_search


@tool
def search_help(query: str) -> str:
    """从本地公开帮助知识库检索相关片段，包含来源编号；找不到时明确返回没有依据。"""
    hits = keyword_search(query)
    return context_text(hits) if hits else 'NO_EVIDENCE'


def run(online=False):
    model = online_model() if online else ScriptedToolModel(responses=[
        AIMessage(content='', tool_calls=[{'name': 'search_help', 'args': {'query': '编译错误 CE'}, 'id': 'help-1', 'type': 'tool_call'}]),
        AIMessage(content='【离线模拟】CE 表示编译错误，先核对公开诊断对应源码行。依据：judge.md。'),
    ])
    agent = create_agent(model=model, tools=[search_help],
                         system_prompt='帮助类问题先检索资料，根据工具证据答复，标明来源；无依据则不猜测。')
    result = agent.invoke({'messages': [{'role': 'user', 'content': 'CE 编译错误是什么意思，怎么办？'}]},
                          config={'recursion_limit': 10})
    return [{'type': m.type, 'content': m.content, 'tool_calls': getattr(m, 'tool_calls', [])}
            for m in result['messages']]


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--online', action='store_true')
    show(run(parser.parse_args().online))
