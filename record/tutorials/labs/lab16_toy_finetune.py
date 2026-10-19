"""Update parameters of a tiny character bigram LM; no GPU or model download.
This is actual gradient training on a toy model, not Transformer/LLM fine-tuning.
"""
import json
import math
import random
from common import output_dir, show

PRETRAIN = 'CE 编译错误。\nRE 运行错误。\nTLE 时间超限。\n'
FINETUNE = 'CE 检查分号。\nRE 检查越界。\nTLE 检查循环。\n'
VALIDATION = 'RE 检查循环。\n'  # A combination not present as a full training sentence.


class BigramLM:
    def __init__(self, vocabulary):
        self.vocabulary = sorted(vocabulary)
        self.ids = {char: i for i, char in enumerate(self.vocabulary)}
        rng = random.Random(7)
        self.weights = [[rng.uniform(-.01, .01) for _ in self.vocabulary] for _ in self.vocabulary]

    def probabilities(self, row):
        maximum = max(row)
        values = [math.exp(x - maximum) for x in row]
        total = sum(values)
        return [x / total for x in values]

    def loss(self, text):
        pairs = list(zip(text, text[1:]))
        return sum(-math.log(max(self.probabilities(self.weights[self.ids[a]])[self.ids[b]], 1e-12))
                   for a, b in pairs) / len(pairs)

    def fit(self, text, epochs=80, learning_rate=.25):
        for _ in range(epochs):
            for a, b in zip(text, text[1:]):
                row = self.weights[self.ids[a]]
                probabilities = self.probabilities(row)
                target = self.ids[b]
                for j, probability in enumerate(probabilities):
                    # Cross-entropy gradient: predicted probability - target one-hot.
                    row[j] -= learning_rate * (probability - int(j == target))


def run():
    model = BigramLM(set(PRETRAIN + FINETUNE + VALIDATION))
    model.fit(PRETRAIN)
    before = model.loss(VALIDATION)
    base_snapshot = [row[:] for row in model.weights]
    model.fit(FINETUNE)
    after = model.loss(VALIDATION)
    changed = any(a != b for ra, rb in zip(base_snapshot, model.weights) for a, b in zip(ra, rb))
    destination = output_dir() / 'toy_language_model.json'
    destination.write_text(json.dumps({'vocabulary': model.vocabulary, 'weights': model.weights}, ensure_ascii=False), encoding='utf-8')
    size, rank = len(model.vocabulary), 2
    return {'validation_loss_before': round(before, 4), 'validation_loss_after': round(after, 4),
            'weights_changed': changed, 'full_matrix_parameters': size * size,
            'lora_rank_2_parameter_count_only': 2 * size * rank, 'saved': str(destination),
            'limitation': '只学习相邻字符统计；没有运行大模型、Transformer、LoRA或QLoRA训练。'}


if __name__ == '__main__':
    show(run())
