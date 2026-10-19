"""Python building blocks used throughout the tutorial; no third-party libraries."""
import asyncio
import json
from typing import TypedDict


class Submission(TypedDict):
    verdict: str
    language: str


def explain(data: Submission) -> dict:
    if data['verdict'] == 'CE':
        return {'summary': '先阅读编译器诊断，再检查对应源码行。'}
    return {'summary': '根据判题结果选择分析方向。'}


async def read_fixture() -> Submission:
    await asyncio.sleep(0)  # Yield control; this is not a network request.
    return {'verdict': 'CE', 'language': 'java'}


async def main():
    submission = await read_fixture()
    result = explain(submission)
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    asyncio.run(main())
