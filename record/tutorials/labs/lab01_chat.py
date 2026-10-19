"""A model invocation; --online is optional and billable."""
import argparse
from langchain_core.messages import HumanMessage, SystemMessage
from common import chat_model, show


def run(online=False):
    model = chat_model('【离线模拟】二分查找每次缩小一半搜索区间。', online)
    messages = [SystemMessage(content='你是算法助教，用中文简短说明概念。'),
                HumanMessage(content='为什么二分查找的时间复杂度是 O(log n)？')]
    answer = model.invoke(messages)
    return {'answer': answer.content, 'usage': answer.usage_metadata,
            'mode': 'online' if online else 'offline'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--online', action='store_true')
    show(run(parser.parse_args().online))
