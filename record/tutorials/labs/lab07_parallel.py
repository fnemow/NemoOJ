"""Fan-out, fan-in and a reducer. Output order is not the synchronization contract."""
import operator
from typing import Annotated, TypedDict
from langgraph.graph import END, START, StateGraph
from common import show


class State(TypedDict, total=False):
    feedback: str
    observations: Annotated[list[str], operator.add]
    summary: str


def build_graph():
    builder = StateGraph(State)
    builder.add_node('classify', lambda s: {'observations': ['分类：提交结果显示问题']})
    builder.add_node('check_evidence', lambda s: {'observations': ['证据：尚缺少提交编号']})
    builder.add_node('merge', lambda s: {'summary': '；'.join(sorted(s['observations']))})
    builder.add_edge(START, 'classify')
    builder.add_edge(START, 'check_evidence')
    builder.add_edge(['classify', 'check_evidence'], 'merge')
    builder.add_edge('merge', END)
    return builder.compile()


if __name__ == '__main__':
    show(build_graph().invoke({'feedback': '结果不显示', 'observations': []}))
