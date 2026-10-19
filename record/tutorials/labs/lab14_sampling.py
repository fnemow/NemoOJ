"""See temperature/top-k/top-p on a toy next-token distribution."""
import argparse
import math
import random
from collections import Counter
from common import show

LOGITS = {'检查': 3.0, '忽略': 1.4, '猜测': 0.8, '删除': -1.0}


def distribution(temperature=1.0, top_k=0, top_p=1.0):
    if temperature <= 0:
        return {max(LOGITS, key=LOGITS.get): 1.0}
    maximum = max(LOGITS.values()) / temperature
    weights = {word: math.exp(score / temperature - maximum) for word, score in LOGITS.items()}
    total = sum(weights.values())
    ranked = sorted(((word, w / total) for word, w in weights.items()), key=lambda p: -p[1])
    if top_k:
        ranked = ranked[:top_k]
        retained_mass = sum(probability for _, probability in ranked)
        ranked = [(word, probability / retained_mass) for word, probability in ranked]
    selected, cumulative = [], 0.0
    for word, probability in ranked:
        selected.append((word, probability))
        cumulative += probability
        if cumulative >= top_p:
            break
    total = sum(p for _, p in selected)
    return {word: p / total for word, p in selected}


def run(temperature=1.0, top_k=0, top_p=1.0):
    probabilities = distribution(temperature, top_k, top_p)
    rng = random.Random(42)
    counts = Counter(rng.choices(list(probabilities), weights=list(probabilities.values()), k=1000))
    return {'probabilities': probabilities, 'sample_1000': dict(counts),
            'note': '玩具采样演示；词概率高不意味着事实正确。'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--temperature', type=float, default=1)
    parser.add_argument('--top-k', type=int, choices=range(5), default=0)
    parser.add_argument('--top-p', type=float, default=1)
    args = parser.parse_args()
    if not 0 < args.top_p <= 1 or args.temperature < 0:
        parser.error('temperature >= 0 and 0 < top-p <= 1 required')
    show(run(args.temperature, args.top_k, args.top_p))
