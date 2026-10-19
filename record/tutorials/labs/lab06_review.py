"""Persist an interrupt in SQLite; resume in a different Python process."""
import argparse
import hashlib
import json
from typing import TypedDict
from langgraph.checkpoint.sqlite import SqliteSaver
from langgraph.graph import END, START, StateGraph
from langgraph.types import Command, interrupt
from common import output_dir, show


class State(TypedDict, total=False):
    task_id: str
    feedback: str
    draft: str
    decision: str
    status: str
    saved: str


def build_graph(checkpointer):
    def draft(state):
        return {'draft': '建议点击提交结果区的刷新按钮；若仍异常请附上提交编号。'}

    def review(state):
        # This node restarts when resumed. Put side effects in a later node.
        decision = interrupt({'draft': state['draft'], 'instruction': '请选择 approve 或 reject'})
        if decision not in {'approve', 'reject'}:
            raise ValueError('Decision must be approve or reject')
        return {'decision': decision}

    def save(state):
        filename = hashlib.sha256(state['task_id'].encode()).hexdigest()[:20] + '.json'
        destination = output_dir() / filename
        payload = {'task_id': state['task_id'], 'reply': state['draft']}
        # One local file per task: replay does not append another reply.
        destination.write_text(json.dumps(payload, ensure_ascii=False), encoding='utf-8')
        return {'status': 'SAVED_LOCALLY', 'saved': str(destination)}

    builder = StateGraph(State)
    builder.add_node('draft', draft)
    builder.add_node('review', review)
    builder.add_node('save', save)
    builder.add_node('reject', lambda s: {'status': 'REJECTED'})
    builder.add_edge(START, 'draft')
    builder.add_edge('draft', 'review')
    builder.add_conditional_edges('review', lambda s: s['decision'], {'approve': 'save', 'reject': 'reject'})
    builder.add_edge('save', END)
    builder.add_edge('reject', END)
    return builder.compile(checkpointer=checkpointer)


def run(action, task, decision='approve'):
    config = {'configurable': {'thread_id': task}}
    with SqliteSaver.from_conn_string(str(output_dir() / 'review.sqlite')) as saver:
        graph = build_graph(saver)
        snapshot = graph.get_state(config)
        if action == 'start':
            if snapshot.values:
                raise ValueError('Task exists. Use status/resume, or a new --task value.')
            return graph.invoke({'task_id': task, 'feedback': '提交结果没有刷新'}, config)
        if not snapshot.values:
            raise ValueError('Task does not exist. Run start first.')
        if action == 'resume':
            if not snapshot.next:
                raise ValueError('Task already completed; resume would be unnecessary.')
            return graph.invoke(Command(resume=decision), config)
        return {'state': snapshot.values, 'next': snapshot.next}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['start', 'status', 'resume'])
    parser.add_argument('--task', default='feedback-demo-1')
    parser.add_argument('--decision', choices=['approve', 'reject'], default='approve')
    args = parser.parse_args()
    show(run(args.action, args.task, args.decision))
