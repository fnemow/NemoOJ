"""Small held-out retrieval set; no LLM-as-judge claims."""
from common import show
from rag_utils import keyword_search, hybrid_search, vector_search

CASES = [
    ('编译错误 CE 缺少分号怎么办', 'judge.md'),
    ('运行错误 RE 除零怎么办', 'judge.md'),
    ('刷新提交结果和提交编号', 'submission.md'),
    ('AI 每日额度 VIP 五份', 'ai.md'),
    ('火星种土豆', None),
]


def evaluate():
    reports = []
    for name, search in [('keyword', keyword_search), ('vector', vector_search), ('hybrid', hybrid_search)]:
        rows = []
        for question, expected in CASES:
            hits = search(question)
            sources = [d.metadata['source'] for d in hits]
            passed = not hits if expected is None else bool(sources and sources[0] == expected)
            rows.append({'question': question, 'expected': expected, 'sources': sources, 'passed': passed})
        reports.append({'retrieval': name, 'top1_or_abstain_accuracy': sum(r['passed'] for r in rows) / len(rows), 'rows': rows})
    return {'cases': len(CASES), 'reports': reports,
            'limitation': '人工小样本只检查检索，不证明真实模型回答质量或所有查询的准确率。'}


if __name__ == '__main__':
    show(evaluate())
