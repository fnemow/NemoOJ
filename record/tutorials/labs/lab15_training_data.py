"""Prepare synthetic SFT data, split by problem group, and validate duplicates."""
import hashlib
import json
from common import output_dir, show


def make_rows():
    topics = [('CE', '编译错误应先检查公开诊断和源码语法。'),
              ('RE', '运行错误应根据诊断核对越界、空值和除零。'),
              ('TLE', '超时应核对复杂度、循环终止条件及重复计算。'),
              ('AC', '通过后可依据源码分析复杂度，不代表全局最优。')]
    return [{'group': verdict, 'messages': [
        {'role': 'system', 'content': '你是算法助教，不编造隐藏用例。'},
        {'role': 'user', 'content': f'{verdict} {question}'},
        {'role': 'assistant', 'content': answer},
    ]} for verdict, answer in topics for question in ['是什么意思？', '应当如何开始排查？', '先看哪些证据？']]


def prepare():
    rows = make_rows()
    fingerprints = [hashlib.sha256(json.dumps(r['messages'], ensure_ascii=False).encode()).hexdigest() for r in rows]
    assert len(set(fingerprints)) == len(rows)
    train = [r for r in rows if r['group'] != 'AC']
    validation = [r for r in rows if r['group'] == 'AC']
    assert {r['group'] for r in train}.isdisjoint({r['group'] for r in validation})
    for name, split in [('train', train), ('validation', validation)]:
        with (output_dir() / f'{name}.jsonl').open('w', encoding='utf-8') as stream:
            for row in split:
                assert [m['role'] for m in row['messages']] == ['system', 'user', 'assistant']
                stream.write(json.dumps(row, ensure_ascii=False) + '\n')
    return {'train': len(train), 'validation': len(validation), 'groups_disjoint': True,
            'trained_model': False, 'output': str(output_dir()),
            'limitation': '仅用于学会数据格式与切分；12条合成样本不足以训练生产模型。'}


if __name__ == '__main__':
    show(prepare())
