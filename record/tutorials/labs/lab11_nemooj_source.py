"""Exercise the CURRENT project graph with its own mock. No cloud/API connection."""
import argparse
import asyncio
import json
import sys
from pathlib import Path
from common import show

PROJECT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(PROJECT / 'nemo-agent'))
from nemo_agent.analysis_graph import MockStructuredModel, create_analysis_graph
from nemo_agent.schemas import FeedbackDecision


class TutorialMockModel(MockStructuredModel):
    """Adapt the fixed feedback response to the graph's sanitized payload.
    The project Mock checks taskType, but that field is not sent by this node.
    This adapter is local teaching code; no production module is changed.
    """
    async def ainvoke(self, messages):
        data = json.loads(messages[-1].content.split('\n', 1)[-1])
        if 'faqCatalog' in data:
            return {'parsed': FeedbackDecision(canResolve=True, faqKey='submission_refresh', escalationReason=''), 'raw': None}
        return await super().ainvoke(messages)


def fixture(task_type):
    if task_type == 'ANALYSIS':
        return {'taskType': task_type, 'taskId': 'local-tutorial', 'ownerUserId': 1,
                'submissionId': 1, 'code': 'class Solution { int solve(){ return 3; } }',
                'language': 'java', 'executionMode': 'FUNCTION', 'verdict': 'AC',
                'runtime': {'timeMs': 12, 'memoryKiB': 2048},
                'problemTitle': '教程假题', 'problemStatement': '仅验证数据流，不验证算法。',
                'publicSamples': []}
    if task_type == 'PLAN':
        return {'taskType': task_type, 'request': {'days': 3, 'sourceMode': 'CATALOG'},
                'catalog': [{'problemNo': n, 'title': f'本地样例{n}'} for n in [1, 3, 13]],
                'completedProblemNos': [], 'currentItems': []}
    if task_type == 'FEEDBACK':
        return {'taskType': task_type, 'content': '提交结果不显示，怎么刷新？',
                'faqCatalog': [{'key': 'submission_refresh', 'reply': '请点击结果区域的刷新按钮。'}]}
    if task_type == 'INSPECTION':
        return {'taskType': task_type, 'inspectionProblem': {'problemNo': 3, 'title': '二分查找',
                                                'content': '在升序数组中查找目标。', 'tags': ['二分查找']}}
    return {'taskType': 'PRIVATE_PROBLEM', 'request': {'goal': '基础整数求和'}}


async def run(task_type='ANALYSIS'):
    graph = create_analysis_graph(TutorialMockModel())
    result = await graph.ainvoke({'context': fixture(task_type)})
    return {'task_type': task_type, 'mode': 'PROJECT_MOCK_ONLY',
            'result': {k: v for k, v in result.items() if k != 'context'}}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--type', choices=['ANALYSIS', 'PLAN', 'FEEDBACK', 'INSPECTION', 'PRIVATE_PROBLEM'], default='ANALYSIS')
    show(asyncio.run(run(parser.parse_args().type)))
