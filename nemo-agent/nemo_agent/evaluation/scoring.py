"""Deterministic checks and explicit missing-evidence gates, never an LLM judge."""
import json
from collections import Counter
from math import ceil, isfinite
from pydantic import field_validator, model_validator

from nemo_agent.schemas import AnalysisReport, FeedbackDecision, MaintenanceProposal, PlanOutput, PrivateProblemDraft, SiteHelpDecision
from .data import EvalCase, OUTPUT_KEYS, ROUTES, digest

SCHEMAS = dict(zip(OUTPUT_KEYS, (AnalysisReport, PrivateProblemDraft, PlanOutput, SiteHelpDecision, FeedbackDecision, MaintenanceProposal)))
COMBINATIONS = {f'{lang}:{mode}' for lang in ('java', 'python', 'cpp') for mode in ('ACM', 'FUNCTION')}


class CorePrivateDraft(PrivateProblemDraft):
    """The current canonical pipeline derives ACM programs from three core sources."""
    @field_validator('referenceSolutions')
    @classmethod
    def bound_reference_sources(cls, value):
        expected = {f'{lang}:FUNCTION' for lang in ('java', 'python', 'cpp')}
        if set(value) != expected or any(not isinstance(s, str) or not s.strip() or len(s) > 50000 for s in value.values()):
            raise ValueError('three complete core sources required')
        return value

    @model_validator(mode='after')
    def canonical_protocol(self):
        if self.inputProtocol != 'CANONICAL_LINES_V1':
            raise ValueError('core references require canonical input protocol')
        return self


def score_case(case: EvalCase, row: dict, review: dict | None = None) -> dict:
    checks = []
    def check(name, passed, hard=False):
        checks.append({'name': name, 'passed': bool(passed), 'hard': hard})
    output = row.get('output', {})
    result = {'caseId': case.id, 'repetition': row.get('repetition', 1), 'task': case.task,
              'checks': checks, 'pending': [], 'outputSha256': digest(output)}
    if row.get('status') != 'completed':
        check('execution_completed', False)
        if row.get('reason') == 'InputIsolationError':
            check('input_channel_isolation', False, True)
        result['status'] = 'skipped' if row.get('status') == 'skipped' else 'failed'
        return result
    trace = row.get('trace', [])
    allowed = {ROUTES[case.task]}
    entries = allowed.copy()
    if case.task == 'PRIVATE_PROBLEM':
        entries |= {'select_private_template', 'specify_private_problem', 'specify_python_problem'}
        allowed |= entries | {'implement_private_problem', 'implement_cross_language', 'validate_private_problem', 'prepare_repair', 'reject_private_problem'}
    check('route', bool(set(trace) & entries))
    check('allowed_graph_nodes', bool(trace) and set(trace) <= allowed, True)
    text = json.dumps(output, ensure_ascii=False)
    check('no_forbidden_disclosure', not any(t in text for t in case.expected.forbidden_text), True)
    key = OUTPUT_KEYS[case.task]
    # Unexpected result channels are a hard boundary failure.
    check('channel_isolation', set(output) == {key}, True)
    try:
        schema = SCHEMAS[case.task]
        if case.task == 'PRIVATE_PROBLEM' and isinstance(output.get(key), dict) and set(output[key].get('referenceSolutions', {})) == {f'{lang}:FUNCTION' for lang in ('java', 'python', 'cpp')}:
            schema = CorePrivateDraft
        payload = schema.model_validate(output.get(key)).model_dump()
    except (ValueError, TypeError):
        check('schema', False, True)
        result['status'] = 'failed'
        return result
    check('schema', True)
    expected = case.expected
    if case.task == 'SITE_HELP':
        check('help_decision', payload['kind'] in expected.kinds)
        check('relevant_sections', not expected.sections or bool(set(expected.sections) & set(payload['sections'])))
    elif case.task == 'FEEDBACK':
        check('feedback_decision', payload['canResolve'] == expected.can_resolve)
        check('verified_faq', not expected.faq_keys or payload['faqKey'] in expected.faq_keys)
        check('escalation_explained', payload['canResolve'] or bool(payload['escalationReason'].strip()))
    elif case.task == 'ANALYSIS':
        count = len(case.context['code'].splitlines())
        locations = {v['line'] for v in payload['locations'] if v['line'] is not None}
        check('source_line_bounds', all(1 <= n <= count for n in locations))
        if expected.lines:
            check('expected_location', bool(locations & set(expected.lines)))
        check('verdict_consistent', payload['verdictEvidence'].get('verdict') == case.context['verdict'])
        if expected.time_complexities:
            clean = lambda s: s.lower().replace(' ', '').replace('²', '^2')
            check('known_time_complexity', clean(payload['complexity']['time']) in {clean(s) for s in expected.time_complexities})
    elif case.task == 'PLAN':
        request = case.context['request']; items = payload['items']
        catalog = {q['problemNo']: q for q in case.context['catalog']}
        completed = set(case.context.get('completedProblemNos', []))
        done_slots = {(i['dayNumber'], i['orderNumber']) for i in case.context.get('currentItems', []) if i.get('status') == 'DONE'}
        slots = [(i['dayNumber'], i['orderNumber']) for i in items]
        numbers = [i['problemNo'] or i['fallbackProblemNo'] for i in items]
        check('real_available_problems', all(n in catalog and n not in completed for n in numbers), True)
        check('unique_problems_and_slots', len(numbers) == len(set(numbers)) and len(slots) == len(set(slots)))
        check('preserve_completed_items', not (set(slots) & done_slots))
        check('catalog_only', request.get('sourceMode') == 'MIXED' or all(i['privateGoal'] is None for i in items), True)
        check('day_coverage', {d for d, _ in slots} | {d for d, _ in done_slots} == set(range(1, request['days'] + 1)))
        check('day_bounds', all(1 <= d <= request['days'] for d, _ in slots))
        tags = set().union(*(set(catalog[n].get('tags', [])) for n in numbers if n in catalog))
        tags |= set().union(*(set(i.get('tags', [])) for i in case.context.get('currentItems', []) if i.get('status') == 'DONE'))
        check('topic_coverage', set(request.get('tags', [])) <= tags)
    elif case.task == 'PRIVATE_PROBLEM':
        check('requested_difficulty', payload['difficulty'] == case.context['request']['difficulty'])
        template = output[key].get('generation', {}).get('templateId')
        check('no_unrelated_template', not expected.free_generation or not template)
        evidence = row.get('validation') or {}
        if not evidence:
            result['pending'].append('sandbox_and_independent_oracle')
        else:
            check('validation_bound_to_output', evidence.get('outputSha256') == result['outputSha256'], True)
            check('real_sandbox_scope', evidence.get('scope') == 'sandbox' and evidence.get('simulated') is False, True)
            check('six_runtime_combinations', set(evidence.get('combinations', [])) == COMBINATIONS)
            check('sandbox_passed', evidence.get('passed') is True)
            check('independent_oracle', evidence.get('oracleValidated') is True)
    else:
        if expected.has_suggestion is not None:
            check('editorial_decision', payload['hasSuggestion'] == expected.has_suggestion)
    if expected.human_criteria:
        if review is None:
            result['pending'].append('human_review')
        else:
            valid = review.get('outputSha256') == result['outputSha256'] and bool(review.get('reviewer', '').strip())
            scores = review.get('scores', {})
            valid = valid and set(scores) == set(expected.human_criteria) and all(type(v) is int and 0 <= v <= 2 for v in scores.values())
            check('review_bound_and_complete', valid)
            if valid:
                # Every criterion must be at least partly met; average >= 1.5/2.
                check('human_quality', min(scores.values()) >= 1 and sum(scores.values()) / len(scores) >= 1.5)
    result['status'] = 'failed' if any(not c['passed'] for c in checks) else 'pending' if result['pending'] else 'passed'
    return result


def percentile(values, fraction):
    return sorted(values)[max(0, ceil(len(values) * fraction) - 1)] if values else None


def make_report(cases, records, reviews, metadata):
    case_map = {c.id: c for c in cases}
    def indexed(rows):
        mapping = {}
        for row in rows:
            key = row['caseId'], row.get('repetition', 1)
            if key in mapping or key[0] not in case_map or type(key[1]) is not int or key[1] < 1:
                raise ValueError('unknown or duplicate result/review identity')
            mapping[key] = row
        return mapping
    results = indexed(records); marked = indexed(reviews)
    for row in records:
        if row.get('status') not in {'completed', 'error', 'skipped'}:
            raise ValueError('unknown execution status')
        elapsed = row.get('elapsedMs')
        if elapsed is not None and (type(elapsed) not in (int, float) or not isfinite(elapsed) or elapsed < 0):
            raise ValueError('invalid elapsed time')
        usage = row.get('usage', {})
        for key in ('calls', 'unknownCalls', 'inputTokens', 'outputTokens'):
            if key in usage and (type(usage[key]) is not int or usage[key] < 0):
                raise ValueError('invalid usage receipt')
        if usage.get('unknownCalls', 0) > usage.get('calls', 0):
            raise ValueError('unknown calls exceed recorded calls')
        for key in ('knownCostUsd', 'costUsd'):
            if usage.get(key) is not None and (not isfinite(float(usage[key])) or float(usage[key]) < 0):
                raise ValueError('invalid cost receipt')
    repeats = metadata['repetitions']
    expected = {(c.id, r) for c in cases for r in range(1, repeats + 1)}
    if set(results) - expected or set(marked) - expected:
        raise ValueError('result/review repetition outside declared run')
    rows = [results.get(k, {'caseId': k[0], 'repetition': k[1], 'status': 'skipped', 'reason': 'missing_result'}) for k in sorted(expected)]
    scored = [dict(score_case(case_map[r['caseId']], r, marked.get((r['caseId'], r['repetition']))), cacheState=r.get('cacheState', 'unknown')) for r in rows]
    counts = Counter(s['status'] for s in scored)
    mock = metadata['mode'] == 'mock'
    hard = sum(any(c['hard'] and not c['passed'] for c in s['checks']) for s in scored)
    durations = [r['elapsedMs'] for r in rows if r.get('status') != 'skipped' and isinstance(r.get('elapsedMs'), (int, float))]
    costs = [r.get('usage', {}).get('knownCostUsd', r.get('usage', {}).get('costUsd')) for r in rows]
    known_cost = sum(float(c) for c in costs if c is not None)
    unknown = sum(r.get('usage', {}).get('unknownCalls', 0) for r in rows)
    complete_cost = not unknown and all(r.get('status') == 'skipped' or r.get('usage', {}).get('costUsd') is not None for r in rows)
    report = {'formatVersion': 1, **metadata, 'qualityStatus': 'NOT_MEASURED' if mock else 'FAIL' if hard or counts['failed'] else 'INCOMPLETE' if counts['pending'] or counts['skipped'] else 'PASS',
              'summary': {'expectedRuns': len(rows), **{k: counts[k] for k in ('passed', 'failed', 'pending', 'skipped')},
                          'completedRuns': sum(r.get('status') == 'completed' for r in rows), 'hardFailures': hard,
                          'passRate': None if mock else counts['passed'] / len(rows),
                          'latencyMs': {'p50': percentile(durations, .5), 'p95': percentile(durations, .95)},
                          'knownCostUsd': known_cost, 'costComplete': complete_cost,
                          'costPerPassedRunUsd': known_cost / counts['passed'] if counts['passed'] and complete_cost and not mock else None,
                          'modelCalls': sum(r.get('usage', {}).get('calls', 0) for r in rows),
                          'providerCalls': 0 if mock else sum(r.get('usage', {}).get('calls', 0) for r in rows), 'unknownCalls': unknown},
              'byTask': {}, 'byCacheState': {}, 'cases': scored,
              'stability': {c.id: {'passed': sum(s['caseId'] == c.id and s['status'] == 'passed' for s in scored), 'repetitions': repeats} for c in cases}}
    for field, dest in [('task', 'byTask'), ('cacheState', 'byCacheState')]:
        for key in sorted({case_map[r['caseId']].task if field == 'task' else r.get(field, 'unknown') for r in rows}):
            indices = [i for i, r in enumerate(rows) if (case_map[r['caseId']].task if field == 'task' else r.get(field, 'unknown')) == key]
            report[dest][key] = dict(Counter(scored[i]['status'] for i in indices))
    return report
