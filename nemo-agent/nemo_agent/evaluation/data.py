import hashlib
import json
from pathlib import Path
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

TASKS = ('ANALYSIS', 'PRIVATE_PROBLEM', 'PLAN', 'SITE_HELP', 'FEEDBACK', 'INSPECTION')
OUTPUT_KEYS = dict(zip(TASKS, ('report', 'private_problem', 'plan', 'site_help', 'feedback', 'maintenance_proposal')))
ROUTES = dict(zip(TASKS, ('analyze', 'generate_private_problem', 'generate_plan', 'handle_site_help', 'handle_feedback', 'inspect_public_problem')))


class Expectations(BaseModel):
    model_config = ConfigDict(extra='forbid')
    kinds: list[str] = Field(default_factory=list)
    sections: list[str] = Field(default_factory=list)
    can_resolve: bool | None = None
    faq_keys: list[str] = Field(default_factory=list)
    lines: list[int] = Field(default_factory=list)
    time_complexities: list[str] = Field(default_factory=list)
    forbidden_text: list[str] = Field(default_factory=list)
    free_generation: bool = False
    has_suggestion: bool | None = None
    human_criteria: list[str] = Field(default_factory=list)


class EvalCase(BaseModel):
    model_config = ConfigDict(extra='forbid')
    id: str = Field(pattern=r'^[a-z][a-z0-9_-]{2,79}$')
    task: Literal['ANALYSIS', 'PRIVATE_PROBLEM', 'PLAN', 'SITE_HELP', 'FEEDBACK', 'INSPECTION']
    split: Literal['regression', 'holdout']
    title: str = Field(min_length=3)
    context: dict[str, Any]
    expected: Expectations
    reference_notes: str = Field(min_length=12)
    tags: list[str] = Field(min_length=1)

    @model_validator(mode='after')
    def matching_task(self):
        if self.context.get('taskType') != self.task:
            raise ValueError('context taskType does not match the suite')
        if self.task in {'ANALYSIS', 'PRIVATE_PROBLEM', 'PLAN', 'INSPECTION'} and not self.expected.human_criteria:
            raise ValueError('open-ended quality requires human criteria')
        if self.task == 'SITE_HELP' and not self.expected.kinds:
            raise ValueError('site help requires expected decisions')
        return self


def digest(value: Any) -> str:
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def corpus_sha256(path: Path) -> str:
    """Pin corpus bytes with LF endings, independent of Git checkout settings."""
    return hashlib.sha256(path.read_bytes().replace(b'\r\n', b'\n')).hexdigest()


def evaluator_fingerprint() -> str:
    return digest({p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(Path(__file__).parent.glob('*.py'))})


def read_jsonl(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding='utf-8-sig').splitlines() if line.strip()]


def default_dataset() -> Path:
    return Path(__file__).resolve().parents[2] / 'evaluation' / 'business-v1.jsonl'


def load_cases(path: Path) -> tuple[list[EvalCase], str]:
    cases = [EvalCase.model_validate(row) for row in read_jsonl(path)]
    if not cases or len({c.id for c in cases}) != len(cases):
        raise ValueError('dataset must be nonempty and case ids unique')
    return cases, digest([c.model_dump() for c in cases])
