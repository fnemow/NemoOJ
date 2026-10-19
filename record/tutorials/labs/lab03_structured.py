"""Validate shape first; a valid schema does not prove factual correctness."""
import argparse
from typing import Literal
from pydantic import BaseModel, Field, ValidationError
from common import chat_model, online_model, show


class Review(BaseModel):
    verdict: Literal['AC', 'CE', 'RE', 'TLE']
    summary: str = Field(min_length=5, max_length=500)
    suggestions: list[str] = Field(min_length=1, max_length=3)
    uncertainty: str = Field(min_length=1, max_length=200)


def run(online=False, invalid=False):
    if invalid:
        try:
            Review.model_validate({'verdict': 'MAGIC', 'summary': '短'})
        except ValidationError as error:
            return {'rejected': True, 'invalid_fields': [list(e['loc']) for e in error.errors()]}
    if online:
        model = online_model().with_structured_output(
            Review, method='function_calling', include_raw=True)
        answer = model.invoke('已知 Java 提交 CE，公开诊断为第4行缺少分号。给出有限建议，不编造代码。')
        if answer.get('parsed') is None:
            raise ValueError('Provider output did not pass Review validation; inspect parsing_error locally.')
        report = answer['parsed']
    else:
        reply = '{"verdict":"CE","summary":"公开诊断提示第4行缺少分号。","suggestions":["核对第4行语句结束符。"],"uncertainty":"未读取完整代码，不能判断其他问题。"}'
        report = Review.model_validate_json(chat_model(reply).invoke('分析诊断').content)
    return report.model_dump()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--online', action='store_true')
    parser.add_argument('--invalid', action='store_true')
    args = parser.parse_args()
    show(run(args.online, args.invalid))
