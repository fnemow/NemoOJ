"""A genuine create_agent tool loop with a simulated or real model."""
import argparse
import json
from langchain.agents import create_agent
from langchain_core.messages import AIMessage
from langchain_core.tools import tool
from common import ROOT, ScriptedToolModel, online_model, show

CATALOG = json.loads((ROOT / 'data/catalog.json').read_text(encoding='utf-8'))


@tool
def find_problems(tag: str, limit: int = 3) -> str:
    """按算法标签查询本地公开题目，返回自然题号和标题；limit 为1至5。"""
    limit = max(1, min(limit, 5))
    return json.dumps([q for q in CATALOG if tag in q['tags']][:limit], ensure_ascii=False)


@tool
def get_problem(problem_no: int) -> str:
    """读取给定自然题号的本地公开题目元数据；不存在时返回错误。"""
    return json.dumps(next((q for q in CATALOG if q['problem_no'] == problem_no),
                           {'error': 'NOT_FOUND'}), ensure_ascii=False)


def build_agent(online=False):
    model = online_model() if online else ScriptedToolModel(responses=[
        AIMessage(content='', tool_calls=[{'name': 'find_problems', 'args': {'tag': '二分查找'}, 'id': 'call-search', 'type': 'tool_call'}]),
        AIMessage(content='', tool_calls=[{'name': 'get_problem', 'args': {'problem_no': 3}, 'id': 'call-detail', 'type': 'tool_call'}]),
        AIMessage(content='【离线模拟】建议练习第3题“二分查找”，重点检查区间边界。'),
    ])
    return create_agent(model=model, tools=[find_problems, get_problem],
                        system_prompt='你是算法学习助手。先查询公开题库再推荐，不虚构题号，不给完整题解。')


def run(online=False):
    result = build_agent(online).invoke(
        {'messages': [{'role': 'user', 'content': '帮我找一道二分查找题，再读取它的详情。'}]},
        config={'recursion_limit': 12})
    return [{'type': m.type, 'content': m.content,
             'tool_calls': getattr(m, 'tool_calls', [])} for m in result['messages']]


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--online', action='store_true')
    show(run(parser.parse_args().online))
