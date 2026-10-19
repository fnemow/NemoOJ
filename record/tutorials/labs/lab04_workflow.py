"""A fixed graph with a conditional route, bounded correction loop, and updates."""
import argparse
import operator
from typing import Annotated, Literal, TypedDict
from langgraph.graph import END, START, StateGraph
from pydantic import BaseModel, Field, ValidationError
from common import show


class State(TypedDict, total=False):
    verdict: str
    direction: str
    attempt: int
    candidate: dict
    report: dict
    valid: bool
    status: str
    trace: Annotated[list[str], operator.add]


class Report(BaseModel):
    direction: Literal['debug', 'optimize']
    suggestion: str = Field(min_length=8)


def build_graph():
    def prepare(state):
        if state['verdict'] not in {'AC', 'CE', 'RE', 'TLE'}:
            raise ValueError('Supported verdicts: AC CE RE TLE')
        return {'attempt': 0, 'trace': ['prepare']}

    def choose(state):
        return 'optimize' if state['verdict'] == 'AC' else 'debug'

    def debug(state):
        return {'direction': 'debug', 'trace': ['debug']}

    def optimize(state):
        return {'direction': 'optimize', 'trace': ['optimize']}

    def draft(state):
        attempt = state['attempt'] + 1
        # A deliberately short first answer demonstrates a validation retry.
        suggestion = '短' if attempt == 1 else '结合公开证据检查边界和复杂度，不猜测隐藏用例。'
        return {'attempt': attempt, 'candidate': {'direction': state['direction'], 'suggestion': suggestion},
                'trace': [f'draft:{attempt}']}

    def validate(state):
        try:
            report = Report.model_validate(state['candidate']).model_dump()
            return {'valid': True, 'report': report, 'trace': ['validate:pass']}
        except ValidationError:
            return {'valid': False, 'trace': ['validate:fail']}

    def next_step(state):
        return 'finish' if state['valid'] else 'draft' if state['attempt'] < 2 else 'fail'

    builder = StateGraph(State)
    for name, fn in [('prepare', prepare), ('debug', debug), ('optimize', optimize),
                     ('draft', draft), ('validate', validate),
                     ('finish', lambda s: {'status': 'SUCCEEDED', 'trace': ['finish']}),
                     ('fail', lambda s: {'status': 'FAILED', 'trace': ['fail']})]:
        builder.add_node(name, fn)
    builder.add_edge(START, 'prepare')
    builder.add_conditional_edges('prepare', choose, {'debug': 'debug', 'optimize': 'optimize'})
    builder.add_edge('debug', 'draft')
    builder.add_edge('optimize', 'draft')
    builder.add_edge('draft', 'validate')
    builder.add_conditional_edges('validate', next_step, {'finish': 'finish', 'draft': 'draft', 'fail': 'fail'})
    builder.add_edge('finish', END)
    builder.add_edge('fail', END)
    return builder.compile()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--verdict', choices=['AC', 'CE', 'RE', 'TLE'], default='CE')
    parser.add_argument('--stream', action='store_true')
    args = parser.parse_args()
    graph = build_graph()
    data = {'verdict': args.verdict, 'trace': []}
    if args.stream:
        for update in graph.stream(data, stream_mode='updates'):
            show(update)
    else:
        show(graph.invoke(data, config={'recursion_limit': 20}))
