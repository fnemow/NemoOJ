"""Small inspectable retrievers. Teaching vectors are NOT learned embeddings."""
import math
import getpass
import os
import re
from collections import Counter
from langchain_core.documents import Document
from langchain_core.embeddings import Embeddings
from langchain_core.vectorstores import InMemoryVectorStore
from langchain_text_splitters import RecursiveCharacterTextSplitter
from common import ROOT


def documents():
    pages = [Document(page_content=p.read_text(encoding='utf-8'),
                      metadata={'source': p.name}) for p in sorted((ROOT / 'data/help').glob('*.md'))]
    splitter = RecursiveCharacterTextSplitter(chunk_size=220, chunk_overlap=40,
                                              separators=['\n\n', '\n', '。', ' ', ''])
    chunks = splitter.split_documents(pages)
    for index, chunk in enumerate(chunks):
        chunk.metadata['chunk_id'] = f'{chunk.metadata["source"]}:{index}'
    return chunks


def terms(text):
    tokens = re.findall(r'[a-z0-9_]+', text.lower())
    for part in re.findall(r'[\u4e00-\u9fff]+', text):
        tokens.extend(part[i:i + 2] for i in range(len(part) - 1))
    return set(tokens) - {'怎么', '如何', '什么', '是否', '可以', '一个'}


def keyword_search(query, chunks=None, k=3):
    chunks = documents() if chunks is None else chunks
    wanted = terms(query)
    ranked = [(len(wanted & terms(d.page_content)), d) for d in chunks]
    ranked.sort(key=lambda item: (-item[0], item[1].metadata['chunk_id']))
    return [d for score, d in ranked[:k] if score > 0]


class TeachingEmbeddings(Embeddings):
    """Six hand-authored feature counts, useful for learning the vector interface only."""
    groups = [
        ['ce', '编译', '分号', '语法'], ['re', '运行', '异常', '除零'],
        ['tle', '超时', '时间超限', '死循环'], ['刷新', '排队', '提交编号'],
        ['额度', 'vip', '每日', '五份'], ['费用', 'token', '收费', '成本'],
    ]

    def embed_query(self, text):
        lower = text.lower()
        words = Counter(re.findall(r'[a-z0-9_]+', lower))
        values = [float(sum(words[w] if w.isascii() else lower.count(w) for w in group))
                  for group in self.groups]
        norm = math.sqrt(sum(x * x for x in values)) or 1
        return [x / norm for x in values]

    def embed_documents(self, texts):
        return [self.embed_query(text) for text in texts]


def embeddings(real=False):
    if not real:
        return TeachingEmbeddings()
    from langchain_openai import OpenAIEmbeddings
    from urllib.parse import urlparse
    model, key, base = (os.getenv('TUTORIAL_EMBED_' + name, '') for name in ['MODEL', 'API_KEY', 'BASE_URL'])
    if not model or urlparse(base).scheme != 'https':
        raise ValueError('Real embeddings need separate TUTORIAL_EMBED_MODEL/BASE_URL.')
    key = key or getpass.getpass('Embedding API Key（输入隐藏）: ')
    if not key:
        raise ValueError('An embedding API key is required only for --real-embeddings.')
    # Disable client-side tiktoken assumptions for an arbitrary compatible provider.
    return OpenAIEmbeddings(model=model, api_key=key, base_url=base,
                            tiktoken_enabled=False, check_embedding_ctx_length=False,
                            max_retries=0, request_timeout=45)


def vector_search(query, real=False, chunks=None, k=3):
    chunks = documents() if chunks is None else chunks
    embedding = embeddings(real)
    if not real and not any(embedding.embed_query(query)):
        return []
    store = InMemoryVectorStore(embedding=embedding)
    store.add_documents(chunks)
    return [doc for doc, score in store.similarity_search_with_score(query, k=k) if score >= 0.20]


def hybrid_search(query, chunks=None, k=3):
    chunks = documents() if chunks is None else chunks
    lists = [keyword_search(query, chunks, k=5), vector_search(query, chunks=chunks, k=5)]
    scores, by_id = {}, {}
    for hits in lists:
        for rank, doc in enumerate(hits, start=1):
            key = doc.metadata['chunk_id']
            scores[key] = scores.get(key, 0) + 1 / (60 + rank)
            by_id[key] = doc
    return [by_id[key] for key in sorted(scores, key=lambda key: (-scores[key], key))[:k]]


def context_text(hits):
    return '\n\n'.join(f'[{d.metadata["chunk_id"]}]\n{d.page_content}' for d in hits)
