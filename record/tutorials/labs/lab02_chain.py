"""LCEL: prompt -> model -> output parser."""
import argparse
from langchain_core.output_parsers import StrOutputParser
from langchain_core.prompts import ChatPromptTemplate
from common import chat_model, show


def run(online=False):
    prompt = ChatPromptTemplate.from_messages([
        ('system', '你是算法助教。讲解 {topic}，适合 {level}。不要提供完整题解。'),
        ('human', '{question}'),
    ])
    model = chat_model('【离线模拟】用左右指针表示待查区间，比较中点后缩小区间。', online)
    chain = prompt | model | StrOutputParser()
    return {'answer': chain.invoke({'topic': '二分查找', 'level': '初学者',
                                   'question': '我容易弄错左右边界，该怎么练习？'})}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--online', action='store_true')
    show(run(parser.parse_args().online))
