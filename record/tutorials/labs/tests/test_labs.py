"""Meaningful offline acceptance: real tool execution, routes, persistence, retrieval.
Reject non-loopback connects in the test process; use offline fixed fixtures.
"""
import asyncio
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
os.environ['TUTORIAL_TRACE'] = 'false'

from common import online_model
from lab01_chat import run as chat
from lab02_chain import run as chain
from lab03_structured import run as structured
from lab04_workflow import build_graph as workflow
from lab05_agent import run as agent
from lab06_review import run as review
from lab07_parallel import build_graph as parallel
from lab08_rag import answer_question
from lab09_vector_rag import run as vector_rag
from lab10_rag_agent import run as rag_agent
from lab11_nemooj_source import run as project_graph
from lab12_dev_workflow import run as dev_workflow
from lab13_evaluate import evaluate
from lab14_sampling import distribution
from lab15_training_data import prepare
from lab16_toy_finetune import run as finetune
from rag_utils import documents, keyword_search, vector_search, hybrid_search


class LabTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.env = patch.dict(os.environ, {'TUTORIAL_OUTPUT_DIR': self.temporary.name, 'TUTORIAL_TRACE': 'false'})
        self.env.start()
        self.network_attempts = 0
        original_connect = socket.socket.connect
        def forbidden(*args, **kwargs):
            # Windows asyncio uses a loopback socket pair for its event-loop wakeup.
            address = args[1] if len(args) > 1 else kwargs.get('address')
            if isinstance(address, tuple) and address[0] in {'127.0.0.1', '::1'}:
                return original_connect(*args, **kwargs)
            self.network_attempts += 1
            raise AssertionError('An offline exercise tried to connect to the network')
        self.network = patch.object(socket.socket, 'connect', forbidden)
        self.network.start()

    def tearDown(self):
        self.network.stop()
        self.env.stop()
        self.temporary.cleanup()
        self.assertEqual(self.network_attempts, 0)

    def test_chat_and_chain_return_strings(self):
        self.assertIn('离线模拟', chat()['answer'])
        self.assertIn('离线模拟', chain()['answer'])

    def test_schema_accepts_valid_and_rejects_bad_output(self):
        self.assertEqual(structured()['verdict'], 'CE')
        self.assertTrue(structured(invalid=True)['rejected'])

    def test_workflow_routes_and_bounded_repair(self):
        for verdict, direction in [('AC', 'optimize'), ('CE', 'debug'), ('RE', 'debug'), ('TLE', 'debug')]:
            result = workflow().invoke({'verdict': verdict, 'trace': []})
            self.assertEqual(result['report']['direction'], direction)
            self.assertEqual(result['attempt'], 2)
            self.assertIn('validate:fail', result['trace'])
            self.assertEqual(result['status'], 'SUCCEEDED')

    def test_workflow_rejects_unknown_verdict(self):
        with self.assertRaises(ValueError): workflow().invoke({'verdict': 'UNKNOWN', 'trace': []})

    def test_agent_executes_both_tools_and_returns_observations(self):
        messages = agent()
        tools = [m for m in messages if m['type'] == 'tool']
        self.assertEqual(len(tools), 2)
        self.assertEqual(json.loads(tools[1]['content'])['problem_no'], 3)

    def test_sqlite_resume_approval_is_persistent(self):
        started = review('start', 'accept')
        self.assertIn('__interrupt__', started)
        self.assertEqual(review('status', 'accept')['next'], ('review',))
        resumed = review('resume', 'accept', 'approve')
        self.assertEqual(resumed['status'], 'SAVED_LOCALLY')
        self.assertTrue(Path(resumed['saved']).is_file())
        with self.assertRaises(ValueError): review('resume', 'accept', 'approve')

    def test_sqlite_rejection_does_not_write_reply(self):
        review('start', 'reject')
        result = review('resume', 'reject', 'reject')
        self.assertEqual(result['status'], 'REJECTED')
        self.assertEqual(list(Path(self.temporary.name).glob('*.json')), [])

    def test_interrupt_survives_separate_processes(self):
        for action in ['start', 'status', 'resume']:
            result = subprocess.run([sys.executable, '-X', 'utf8', str(ROOT / 'lab06_review.py'),
                                     action, '--task', 'different-process'],
                                    capture_output=True, text=True, encoding='utf-8', timeout=30)
            self.assertEqual(result.returncode, 0, result.stderr)
            payload = json.loads(result.stdout)
        self.assertEqual(payload['status'], 'SAVED_LOCALLY')

    def test_parallel_join_has_both_observations(self):
        result = parallel().invoke({'feedback': '结果不显示', 'observations': []})
        self.assertEqual(len(result['observations']), 2)
        self.assertIn('提交编号', result['summary'])

    def test_chunk_ids_and_three_retrievers(self):
        chunks = documents()
        ids = [d.metadata['chunk_id'] for d in chunks]
        self.assertEqual(len(ids), len(set(ids)))
        for search in [keyword_search, vector_search, hybrid_search]:
            self.assertEqual(search('编译错误 CE')[0].metadata['source'], 'judge.md')
            self.assertEqual(search('火星种土豆'), [])

    def test_no_evidence_abstains_without_a_model(self):
        with patch('lab08_rag.chat_model', side_effect=AssertionError('Must not invoke model')):
            result = answer_question('火星种土豆')
        self.assertFalse(result['model_called'])

    def test_rag_answer_includes_selected_sources(self):
        self.assertTrue(answer_question('CE 编译错误')['sources'])
        self.assertIn('judge.md', vector_rag()['sources'][0])

    def test_agentic_rag_runs_search_tool(self):
        tools = [m for m in rag_agent() if m['type'] == 'tool']
        self.assertEqual(len(tools), 1)
        self.assertIn('judge.md', tools[0]['content'])

    def test_current_project_all_five_task_routes(self):
        expected = {'ANALYSIS': 'report', 'PLAN': 'plan', 'FEEDBACK': 'feedback',
                    'INSPECTION': 'maintenance_proposal', 'PRIVATE_PROBLEM': 'private_problem'}
        for task_type, key in expected.items():
            result = asyncio.run(project_graph(task_type))
            self.assertIn(key, result['result'])

    def test_development_tests_catch_then_repair_last_element_bug(self):
        result = dev_workflow()
        self.assertEqual(result['attempts'], 2)
        self.assertEqual(result['status'], 'READY_FOR_REVIEW')
        self.assertTrue(result['passed'])
        self.assertIn('Ran 3 tests', result['test_output'])

    def test_retrieval_evaluation_records_all_cases(self):
        result = evaluate()
        self.assertEqual(result['cases'], 5)
        for report in result['reports']:
            self.assertEqual(len(report['rows']), 5)
            self.assertEqual(report['top1_or_abstain_accuracy'], 1.0)

    def test_sampling_filters_and_normalization(self):
        self.assertEqual(distribution(0), {'检查': 1.0})
        self.assertEqual(len(distribution(top_k=2)), 2)
        self.assertEqual(distribution(top_k=2, top_p=.8), {'检查': 1.0})
        self.assertAlmostEqual(sum(distribution(top_p=.8).values()), 1)

    def test_sft_data_is_split_by_group_without_overlap(self):
        result = prepare()
        self.assertEqual((result['train'], result['validation']), (9, 3))
        self.assertTrue(result['groups_disjoint'])
        self.assertFalse(result['trained_model'])

    def test_toy_finetuning_updates_parameters(self):
        result = finetune()
        self.assertTrue(result['weights_changed'])
        self.assertLess(result['validation_loss_after'], result['validation_loss_before'])

    def test_online_requires_explicit_valid_configuration(self):
        with patch.dict(os.environ, {'TUTORIAL_MODEL': 'replace-with-your-enabled-model-id'}):
            with self.assertRaises(ValueError): online_model()


if __name__ == '__main__':
    unittest.main()
