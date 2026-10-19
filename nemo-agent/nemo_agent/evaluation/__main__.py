import argparse
import asyncio
import json
from datetime import datetime, timezone
from pathlib import Path

from .data import TASKS, default_dataset, evaluator_fingerprint, load_cases, read_jsonl
from .reporting import compare_reports, write_report
from .scoring import make_report


def main():
    parser = argparse.ArgumentParser(description='NemoOJ isolated business evaluation (no calls by default)')
    sub = parser.add_subparsers(dest='command', required=True)
    for name in ('check', 'export', 'run', 'score'):
        p = sub.add_parser(name)
        p.add_argument('--dataset', type=Path, default=default_dataset())
        p.add_argument('--task', choices=TASKS, action='append')
        p.add_argument('--split', choices=['regression', 'holdout', 'all'], default='regression')
        p.add_argument('--limit', type=int)
        if name != 'check':
            p.add_argument('--out', type=Path, required=True)
            p.add_argument('--repetitions', type=int, default=1)
            p.add_argument('--require-pass', action='store_true')
    run = sub.choices['run']
    run.add_argument('--mode', choices=['mock', 'live'], default='mock')
    run.add_argument('--allow-model-calls', action='store_true')
    run.add_argument('--model', default='')
    run.add_argument('--base-url', default='')
    run.add_argument('--max-calls', type=int, default=0)
    run.add_argument('--max-cost-usd', default='0')
    run.add_argument('--input-price', default='0')
    run.add_argument('--output-price', default='0')
    run.add_argument('--max-output-tokens', type=int, default=4096)
    run.add_argument('--call-timeout', type=float, default=45)
    run.add_argument('--case-timeout', type=float, default=120)
    score = sub.choices['score']
    score.add_argument('--responses', type=Path, required=True)
    score.add_argument('--reviews', type=Path)
    score.add_argument('--source-manifest', type=Path, required=True, help='Original run manifest matching dataset, selection and repetitions')
    compare = sub.add_parser('compare')
    compare.add_argument('before', type=Path); compare.add_argument('after', type=Path)
    compare.add_argument('--fail-on-regression', action='store_true')
    args = parser.parse_args()
    try:
        if args.command == 'compare':
            result = compare_reports(json.loads(args.before.read_text(encoding='utf-8')), json.loads(args.after.read_text(encoding='utf-8')))
            print(json.dumps(result, ensure_ascii=False, indent=2))
            return 2 if args.fail_on_regression and (result['regressions'] or result['hardFailureDelta'] > 0 or not result['qualityComplete']) else 0
        cases, dataset_hash = load_cases(args.dataset)
        cases = [c for c in cases if (not args.task or c.task in args.task) and (args.split == 'all' or c.split == args.split)]
        if args.limit is not None:
            if args.limit < 1: raise ValueError('limit must be positive')
            cases = cases[:args.limit]
        if not cases: raise ValueError('empty selection')
        if args.command == 'check':
            print(json.dumps({'valid': True, 'cases': len(cases), 'datasetSha256': dataset_hash,
                              'byTask': {t: sum(c.task == t for c in cases) for t in TASKS}}, indent=2))
            return 0
        if not 1 <= args.repetitions <= 5: raise ValueError('repetitions must be 1..5')
        metadata = {'formatVersion': 1, 'datasetSha256': dataset_hash, 'evaluatorSha256': evaluator_fingerprint(), 'caseIds': [c.id for c in cases],
                    'repetitions': args.repetitions, 'createdAt': datetime.now(timezone.utc).isoformat()}
        if args.command == 'run':
            from .runner import graph_fingerprint, model_for_run, run_cases
            if args.case_timeout <= 0 or args.call_timeout <= 0 or not 128 <= args.max_output_tokens <= 16000:
                raise ValueError('invalid timeout/output bounds')
            # Validate live opt-in before creating output files, without invoking a provider.
            model = model_for_run(args)
            metadata.update(mode=args.mode, scope='graph_component_no_sandbox', graphSha256=graph_fingerprint(), model=args.model if args.mode == 'live' else 'MockStructuredModel')
            import importlib.metadata
            metadata['versions'] = {name: importlib.metadata.version(name) for name in ('langchain', 'langgraph', 'langchain-openai', 'pydantic')}
            metadata['parameters'] = {'maxOutputTokens': args.max_output_tokens, 'callTimeout': args.call_timeout, 'caseTimeout': args.case_timeout,
                                      'maxCalls': args.max_calls, 'maxCostUsd': args.max_cost_usd, 'inputPrice': args.input_price, 'outputPrice': args.output_price,
                                      'temperature': 0, 'canonicalGeneration': False, 'sandbox': False, 'autoRepair': False}
        elif args.command == 'score':
            source = json.loads(args.source_manifest.read_text(encoding='utf-8'))
            for key in ('datasetSha256', 'evaluatorSha256', 'caseIds', 'repetitions'):
                if source.get(key) != metadata[key]: raise ValueError('source manifest mismatch: ' + key)
            if source.get('mode') not in {'mock', 'live', 'recorded'}: raise ValueError('unknown source mode')
            metadata.update({k: source[k] for k in ('mode', 'scope')})
            metadata.update({k: source[k] for k in ('graphSha256', 'model', 'versions', 'parameters') if k in source})
        else:
            metadata.update(mode='recorded', scope='recorded_e2e')
        (args.out.parent).mkdir(parents=True, exist_ok=True)
        args.out.mkdir(parents=True, exist_ok=False)
        # Persist provenance before invoking models, including interrupted runs.
        (args.out / 'manifest.json').write_text(json.dumps(metadata, indent=2), encoding='utf-8')
        if args.command == 'export':
            requests = [{'caseId': c.id, 'repetition': r, 'context': c.context} for c in cases for r in range(1, args.repetitions + 1)]
            (args.out / 'requests.jsonl').write_text(''.join(json.dumps(row, ensure_ascii=False) + '\n' for row in requests), encoding='utf-8')
            print(json.dumps({'exported': len(requests), 'modelCalls': 0, 'scope': metadata['scope']}))
            return 0
        reviews = read_jsonl(args.reviews) if args.command == 'score' and args.reviews else []
        records = asyncio.run(run_cases(cases, args, args.out, model)) if args.command == 'run' else read_jsonl(args.responses)
        report = make_report(cases, records, reviews, metadata)
        (args.out / 'manifest.json').write_text(json.dumps(metadata, indent=2), encoding='utf-8')
        write_report(args.out, report)
        # Empty scores are deliberately unreviewed. Edit a separate copy with a reviewer.
        templates = []
        by_id = {c.id: c for c in cases}
        for item in report['cases']:
            case = by_id[item['caseId']]
            if case.expected.human_criteria:
                templates.append({'caseId': case.id, 'repetition': item['repetition'], 'outputSha256': item['outputSha256'],
                                  'reviewer': '', 'scores': {c: None for c in case.expected.human_criteria},
                                  'referenceNotes': case.reference_notes, 'comment': ''})
        (args.out / 'reviews-template.jsonl').write_text(''.join(json.dumps(t, ensure_ascii=False) + '\n' for t in templates), encoding='utf-8')
        print(json.dumps({'qualityStatus': report['qualityStatus'], **report['summary']}, ensure_ascii=False, indent=2))
        return 2 if args.require_pass and report['qualityStatus'] != 'PASS' else 0
    except Exception as error:
        # Dataset errors and provider errors must not echo payloads or credentials.
        print(json.dumps({'error': type(error).__name__, 'message': 'evaluation failed; inspect dataset selection/configuration and documentation (payload withheld)'}))
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
