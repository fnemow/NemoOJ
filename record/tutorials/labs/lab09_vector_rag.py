"""Compare keyword/vector/hybrid retrieval; optionally use a real embeddings API."""
import argparse
from common import show
from lab08_rag import answer_question
from rag_utils import documents, hybrid_search, keyword_search, vector_search


def run(question='运行时异常怎么处理？', method='vector', online=False, real_embeddings=False):
    chunks = documents()
    if real_embeddings and method != 'vector':
        raise ValueError('--real-embeddings is supported only for --method vector in this lab.')
    retriever = {'keyword': lambda q: keyword_search(q, chunks),
                 'vector': lambda q: vector_search(q, real_embeddings, chunks),
                 'hybrid': lambda q: hybrid_search(q, chunks)}[method]
    result = answer_question(question, online, retriever)
    result.update({'retrieval': method, 'chunks': len(chunks),
                   'vectors': 'provider' if real_embeddings else 'hand-authored teaching features'})
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--question', default='运行时异常怎么处理？')
    parser.add_argument('--method', choices=['keyword', 'vector', 'hybrid'], default='vector')
    parser.add_argument('--online', action='store_true')
    parser.add_argument('--real-embeddings', action='store_true')
    args = parser.parse_args()
    show(run(args.question, args.method, args.online, args.real_embeddings))
