import copy
import json
from decimal import Decimal
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from nemo_agent.evaluation.data import default_dataset, digest, load_cases
from nemo_agent.evaluation.reporting import compare_reports
from nemo_agent.evaluation.runner import BudgetExceeded, EvalRecorder, InputIsolationError
from nemo_agent.evaluation.scoring import make_report, score_case
from nemo_agent.trusted_templates import build_template


@pytest.fixture
def cases():
    return load_cases(default_dataset())[0]


def find(cases, id):
    return next(c for c in cases if c.id == id)


def row(output, **kwargs):
    return {'status': 'completed', 'caseId': 'help-1', 'repetition': 1, 'output': output,
            'trace': ['handle_site_help'], 'elapsedMs': 100, 'usage': {'calls': 1, 'costUsd': '0.01', 'unknownCalls': 0}, **kwargs}


def test_original_corpus_is_fixed_and_all_business_suites_are_covered(cases):
    assert len(cases) == 60
    assert len({c.id for c in cases}) == 60
    assert {c.task for c in cases} == {'ANALYSIS', 'PRIVATE_PROBLEM', 'PLAN', 'SITE_HELP', 'FEEDBACK', 'INSPECTION'}
    assert sum(c.split == 'holdout' for c in cases) == 19
    pinned = json.loads(default_dataset().with_name('business-v1.manifest.json').read_text(encoding='utf-8'))
    import hashlib
    assert hashlib.sha256(default_dataset().read_bytes()).hexdigest() == pinned['sha256']


def test_site_help_quality_is_separate_from_valid_schema(cases):
    case = find(cases, 'help-7')
    good = row({'site_help': {'kind': 'CODE_REDIRECT', 'sections': []}})
    bad = row({'site_help': {'kind': 'HELP', 'sections': ['submit_flow']}})
    assert score_case(case, good)['status'] == 'passed'
    result = score_case(case, bad)
    assert result['status'] == 'failed'
    assert next(c for c in result['checks'] if c['name'] == 'schema')['passed']


def test_wrong_channel_and_private_disclosure_are_hard_failures(cases):
    result = score_case(find(cases, 'help-1'), row({'site_help': {'kind': 'HELP', 'sections': ['result_refresh']}, 'report': 'SYNTHETIC_SOURCE_CANARY'}))
    assert result['status'] == 'failed'
    assert sum(c['hard'] and not c['passed'] for c in result['checks']) >= 2


def test_private_generation_never_passes_on_schema_alone(cases):
    case = find(cases, 'generation-redblack-easy')
    candidate = build_template('prefix_single', {'goal': '前缀和', 'difficulty': 'EASY'}, 'evaluation-original')
    sample = row({'private_problem': candidate}, trace=['generate_private_problem'])
    result = score_case(case, sample)
    assert result['status'] == 'pending'
    assert set(result['pending']) == {'sandbox_and_independent_oracle', 'human_review'}
    sample['validation'] = {'scope': 'sandbox', 'simulated': True, 'outputSha256': digest(sample['output']), 'passed': True,
                            'combinations': [f'{lang}:{mode}' for lang in ('java', 'python', 'cpp') for mode in ('ACM', 'FUNCTION')], 'oracleValidated': True}
    assert score_case(case, sample)['status'] == 'failed'


def test_manual_review_is_bound_to_the_exact_output_and_every_criterion(cases):
    case = find(cases, 'analysis-java-linear-ac')
    payload = {'verdictEvidence': {'verdict': 'accepted'}, 'likelyCause': '代码通过，线性求和。', 'locations': [], 'suggestions': [],
               'complexity': {'time': 'O(n)', 'space': 'O(1)'}, 'uncertainty': ''}
    sample = row({'report': payload}, trace=['analyze'])
    review = {'reviewer': 'human', 'outputSha256': digest(sample['output']), 'scores': {k: 2 for k in case.expected.human_criteria}}
    assert score_case(case, sample, review)['status'] == 'passed'
    review['outputSha256'] = 'stale'
    assert score_case(case, sample, review)['status'] == 'failed'


def test_missing_runs_and_unknown_costs_cannot_look_like_success(cases):
    case = find(cases, 'help-1')
    sample = row({'site_help': {'kind': 'HELP', 'sections': ['result_refresh']}}, usage={'calls': 1, 'costUsd': None, 'knownCostUsd': '0', 'unknownCalls': 1})
    metadata = {'mode': 'live', 'scope': 'graph_component_no_sandbox', 'repetitions': 2}
    report = make_report([case], [sample], [], metadata)
    assert report['qualityStatus'] == 'INCOMPLETE'
    assert report['summary']['passRate'] == .5
    assert report['summary']['skipped'] == 1
    assert not report['summary']['costComplete']
    assert report['summary']['costPerPassedRunUsd'] is None
    assert make_report([case], [sample], [], {**metadata, 'mode': 'mock'})['qualityStatus'] == 'NOT_MEASURED'


def test_unknown_or_duplicate_responses_are_rejected(cases):
    case = find(cases, 'help-1'); sample = row({'site_help': {'kind': 'HELP', 'sections': ['result_refresh']}})
    with pytest.raises(ValueError):
        make_report([case], [sample, sample], [], {'mode': 'live', 'repetitions': 1})


def test_comparison_requires_identical_dataset_and_cache_conditions(cases):
    case = find(cases, 'help-1'); metadata = {'mode': 'live', 'scope': 'graph_component_no_sandbox', 'repetitions': 1,
                                           'caseIds': [case.id], 'datasetSha256': 'test'}
    before = make_report([case], [row({'site_help': {'kind': 'HELP', 'sections': ['result_refresh']}})], [], metadata)
    after = copy.deepcopy(before); after['cases'][0]['status'] = 'failed'
    assert compare_reports(before, after)['regressions'] == [['help-1', 1]]
    after['datasetSha256'] = 'changed'
    with pytest.raises(ValueError): compare_reports(before, after)
    after = copy.deepcopy(before); after['cases'][0]['cacheState'] = 'warm'
    with pytest.raises(ValueError): compare_reports(before, after)


@pytest.mark.asyncio
async def test_budget_denial_never_invokes_provider_and_unknown_keeps_reservation():
    model = SimpleNamespace(ainvoke=AsyncMock(return_value={'raw': None}))
    recorder = EvalRecorder(live=True, max_calls=1, max_cost='1', input_price='1', output_price='1')
    await recorder.invoke(model, [])
    assert recorder.unknown == 1 and recorder.reserved > 0
    with pytest.raises(BudgetExceeded): await recorder.invoke(model, [])
    assert model.ainvoke.await_count == 1
    too_small = EvalRecorder(live=True, max_calls=2, max_cost='0.001', input_price='1', output_price='1')
    with pytest.raises(BudgetExceeded): await too_small.invoke(model, [])
    assert model.ainvoke.await_count == 1


@pytest.mark.asyncio
async def test_known_receipt_and_provider_failure_remain_distinct():
    recorder = EvalRecorder(live=True, max_calls=3, max_cost='1', input_price='1', output_price='2')
    model = SimpleNamespace(ainvoke=AsyncMock(side_effect=[TimeoutError(), {'raw': SimpleNamespace(usage_metadata={'input_tokens': 100, 'output_tokens': 10})}]))
    before = recorder.snapshot()
    with pytest.raises(TimeoutError): await recorder.invoke(model, [])
    await recorder.invoke(model, [])
    assert recorder.known_cost == Decimal('.00012')
    assert recorder.usage_since(before)['costUsd'] is None
    assert recorder.reserved > recorder.known_cost


@pytest.mark.asyncio
async def test_private_canary_blocks_input_before_network_call():
    recorder = EvalRecorder(); recorder.forbidden_text = ['PRIVATE_CANARY']
    model = SimpleNamespace(ainvoke=AsyncMock())
    with pytest.raises(InputIsolationError):
        await recorder.invoke(model, [SimpleNamespace(content='PRIVATE_CANARY')])
    model.ainvoke.assert_not_awaited()


def test_plan_rejects_fabricated_ids_and_preserves_completed_slots(cases):
    case = find(cases, 'plan-7')
    sample = row({'plan': {'items': [{'dayNumber': 1, 'orderNumber': 1, 'problemNo': 99999, 'reason': '这是一条足够长但虚构的选题说明'}]}}, trace=['generate_plan'])
    result = score_case(case, sample)
    assert result['status'] == 'failed'
    assert any(c['hard'] and not c['passed'] for c in result['checks'])
    assert not next(c for c in result['checks'] if c['name'] == 'preserve_completed_items')['passed']


@pytest.mark.parametrize('change', [{'elapsedMs': -1}, {'elapsedMs': float('nan')}, {'usage': {'calls': -1}}, {'usage': {'costUsd': '-1'}}])
def test_import_rejects_invalid_measurements(cases, change):
    sample = row({'site_help': {'kind': 'HELP', 'sections': ['result_refresh']}}, **change)
    with pytest.raises(ValueError):
        make_report([find(cases, 'help-1')], [sample], [], {'mode': 'recorded', 'repetitions': 1})


def test_cli_mock_execution_writes_provenance_without_claiming_quality(tmp_path, monkeypatch):
    import sys
    from nemo_agent.evaluation.__main__ import main
    out = tmp_path / 'mock'
    monkeypatch.setattr(sys, 'argv', ['evaluation', 'run', '--mode', 'mock', '--task', 'FEEDBACK', '--limit', '1', '--out', str(out)])
    assert main() == 0
    report = json.loads((out / 'report.json').read_text(encoding='utf-8'))
    assert report['qualityStatus'] == 'NOT_MEASURED'
    assert report['summary']['completedRuns'] == 1
    assert report['summary']['modelCalls'] == 1
    assert report['summary']['knownCostUsd'] == 0
    assert report['versions']['langgraph']
    assert (out / 'manifest.json').exists()
    scored = tmp_path / 'scored'
    monkeypatch.setattr(sys, 'argv', ['evaluation', 'score', '--task', 'FEEDBACK', '--limit', '1', '--responses', str(out / 'responses.jsonl'), '--source-manifest', str(out / 'manifest.json'), '--out', str(scored)])
    assert main() == 0
    assert json.loads((scored / 'report.json').read_text(encoding='utf-8'))['qualityStatus'] == 'NOT_MEASURED'


def test_cli_live_is_never_implicitly_enabled(tmp_path, monkeypatch):
    import sys
    from nemo_agent.evaluation.__main__ import main
    monkeypatch.delenv('NEMO_EVAL_API_KEY', raising=False)
    out = tmp_path / 'must-not-call'
    monkeypatch.setattr(sys, 'argv', ['evaluation', 'run', '--mode', 'live', '--model', 'example-model', '--base-url', 'https://example.invalid/v1', '--out', str(out)])
    assert main() == 1
    assert not out.exists()


def test_export_makes_an_isolated_recorded_run_manifest(tmp_path, monkeypatch):
    import sys
    from nemo_agent.evaluation.__main__ import main
    out = tmp_path / 'recorded'
    monkeypatch.setattr(sys, 'argv', ['evaluation', 'export', '--task', 'SITE_HELP', '--limit', '1', '--out', str(out)])
    assert main() == 0
    manifest = json.loads((out / 'manifest.json').read_text(encoding='utf-8'))
    assert manifest['mode'] == 'recorded' and manifest['scope'] == 'recorded_e2e'
    assert manifest['evaluatorSha256']
    request = json.loads((out / 'requests.jsonl').read_text(encoding='utf-8').strip())
    assert request['caseId'] == 'help-1'
    assert 'expected' not in request and not (out / 'report.json').exists()


def test_unexpected_graph_nodes_are_hard_failures(cases):
    sample = row({'site_help': {'kind': 'HELP', 'sections': ['result_refresh']}}, trace=['handle_site_help', 'publish_problem'])
    result = score_case(find(cases, 'help-1'), sample)
    assert result['status'] == 'failed'
    assert next(c for c in result['checks'] if c['name'] == 'allowed_graph_nodes') == {'name': 'allowed_graph_nodes', 'passed': False, 'hard': True}
