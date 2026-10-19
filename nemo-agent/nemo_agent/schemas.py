import json
from typing import Any, Annotated

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from typing import Literal
from json_repair import loads as repair_json_loads


def decode_provider_object(value, limit):
    """Normalize nested tool JSON; semantic validity is always checked afterwards."""
    if isinstance(value,str):
        if len(value)>limit:raise ValueError('tool object too large')
        try:value=json.loads(value)
        except json.JSONDecodeError:
            value=repair_json_loads(value,skip_json_loads=True)
        if not isinstance(value,dict):raise ValueError('tool object required')
    return value


def to_camel(name: str) -> str:
    first, *rest = name.split("_")
    return first + "".join(part.title() for part in rest)


class Location(BaseModel):
    line: int | None = Field(default=None, ge=1)
    symbol: str = Field(default="", max_length=120)
    explanation: str = Field(max_length=1200)


class Suggestion(BaseModel):
    title: str = Field(max_length=160)
    detail: str = Field(max_length=1600)
    impact: str = Field(default="", max_length=500)


class Complexity(BaseModel):
    time: str = Field(default="未知", max_length=120)
    space: str = Field(default="未知", max_length=120)
    explanation: str = Field(default="", max_length=1200)


class AnalysisReport(BaseModel):
    verdictEvidence: dict[str, Any]
    likelyCause: str = Field(max_length=3000)
    locations: list[Location] = Field(max_length=20)
    suggestions: list[Suggestion] = Field(max_length=12)
    complexity: Complexity
    uncertainty: str = Field(max_length=1200)

    @field_validator("verdictEvidence")
    @classmethod
    def bound_evidence(cls, value: dict[str, Any]) -> dict[str, Any]:
        if len(str(value)) > 4000:
            raise ValueError("verdict evidence is too large")
        return value


class TaskState(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)
    task_id: str
    owner_user_id: int
    submission_id: int
    code: str = Field(max_length=524288)
    language: str = Field(max_length=32)
    execution_mode: str = Field(max_length=16)
    verdict: str = Field(max_length=64)
    runtime: dict[str, Any] = Field(default_factory=dict)
    problem_title: str = Field(default="", max_length=512)
    problem_statement: str = Field(default="", max_length=20000)
    public_samples: list[dict[str, str]] = Field(default_factory=list, max_length=20)


class PlanItemDraft(BaseModel):
    dayNumber: int = Field(ge=1, le=30)
    orderNumber: int = Field(ge=1, le=3)
    problemNo: int | None = Field(default=None, gt=0)
    privateGoal: str | None = Field(default=None, min_length=8, max_length=1000)
    fallbackProblemNo: int | None = Field(default=None, gt=0)
    reason: str = Field(min_length=8, max_length=1000)

    @model_validator(mode="after")
    def one_source(self):
        if (self.problemNo is None) == (self.privateGoal is None):
            raise ValueError("choose exactly one public problem or private goal")
        if self.privateGoal is not None and self.fallbackProblemNo is None:
            raise ValueError("private items require a public fallback")
        return self


class PlanOutput(BaseModel):
    items: list[PlanItemDraft] = Field(min_length=1, max_length=90)

    @model_validator(mode="after")
    def bound_private_items(self):
        if sum(item.privateGoal is not None for item in self.items) > 5:
            raise ValueError("a mixed plan may generate at most five private problems")
        return self


class GeneratedCase(BaseModel):
    input: str = Field(max_length=12000)
    output: str = Field(max_length=12000)
    isHidden: bool = True


class GeneratedJudgeConfig(BaseModel):
    model_config = ConfigDict(extra="forbid")
    timeLimit: Literal[2000] = 2000
    memoryLimit: Literal[262144] = 262144
    outputLimitBytes: Literal[1048576] = 1048576


BuiltinGeneratedValueType = Literal[
    "int", "long", "double", "boolean", "string",
    "int[]", "long[]", "double[]", "boolean[]", "string[]",
    "int[][]", "long[][]", "double[][]", "string[][]",
    "list<int>", "list<long>", "list<double>", "list<string>",
    "list_node<int>", "tree_node<int>",
]
GeneratedValueType = BuiltinGeneratedValueType | Annotated[str, Field(pattern=r'^node<[A-Z][A-Za-z0-9]{0,39}>$')]

class CustomNodeField(BaseModel):
    model_config=ConfigDict(extra='forbid')
    name:str=Field(min_length=1,max_length=40,pattern=r'^[A-Za-z][A-Za-z0-9_]*$')
    type:Literal['int','long','double','boolean','string','ref','ref[]']

class CustomNodeDefinition(BaseModel):
    model_config=ConfigDict(extra='forbid')
    name:str=Field(min_length=1,max_length=40,pattern=r'^[A-Z][A-Za-z0-9]*$')
    fields:list[CustomNodeField]=Field(min_length=1,max_length=12)


class GeneratedParameter(BaseModel):
    model_config = ConfigDict(extra="forbid")
    name: str = Field(min_length=1, max_length=40, pattern=r"^[A-Za-z_][A-Za-z0-9_]*$")
    type: GeneratedValueType


class GeneratedComparator(BaseModel):
    model_config = ConfigDict(extra="forbid")
    type: Literal["EXACT", "TEXT", "FLOAT", "UNORDERED_LIST", "MULTISET"] = "EXACT"
    epsilon: float | None = Field(default=None, gt=0, le=1)


class GeneratedProblemSpec(BaseModel):
    model_config = ConfigDict(extra="forbid")
    mode: Literal["function"] = "function"
    className: Literal["Solution"] = "Solution"
    methodName: str = Field(min_length=1, max_length=80, pattern=r"^[A-Za-z_][A-Za-z0-9_]*$")
    parameters: list[GeneratedParameter] = Field(min_length=1, max_length=8)
    returnType: GeneratedValueType
    resultSource: Literal["return"] = "return"
    comparator: GeneratedComparator
    harnessVersion: Literal[1] = 1
    dataStructures:list[CustomNodeDefinition]=Field(default_factory=list,max_length=4)
    judgeCases: list[GeneratedCase] = Field(min_length=20, max_length=20)

    @model_validator(mode="after")
    def validate_function_case_protocol(self):
        from nemo_agent.custom_structures import validate_signature
        validate_signature(self.model_dump())
        names = {parameter.name for parameter in self.parameters}
        if len(names) != len(self.parameters):
            raise ValueError("function parameter names must be unique")
        for case in self.judgeCases:
            try:
                arguments = json.loads(case.input)
                json.loads(case.output)
            except json.JSONDecodeError as exc:
                raise ValueError("function cases must contain valid JSON") from exc
            if not isinstance(arguments, dict) or set(arguments) != names:
                raise ValueError("function case input must be a JSON object keyed by parameter name")
        return self


class PrivateProblemDraft(BaseModel):
    inputProtocol: Literal["CANONICAL_LINES_V1"] | None = None
    title: str = Field(min_length=3, max_length=120)
    tags: list[str] = Field(min_length=1, max_length=8)
    difficulty: Literal["EASY", "MEDIUM", "HARD"]
    contentMarkdown: str = Field(min_length=100, max_length=20000)
    judgeCases: list[GeneratedCase] = Field(min_length=20, max_length=20)
    judgeConfig: GeneratedJudgeConfig
    problemSpec: GeneratedProblemSpec
    referenceSolutions: dict[str, str]

    @model_validator(mode="after")
    def validate_acm_case_protocol(self):
        if not any(not case.isHidden for case in self.judgeCases) or not any(case.isHidden for case in self.judgeCases):
            raise ValueError("ACM cases need public samples and hidden cases")
        for case in self.judgeCases:
            if self.inputProtocol is None and case.input.lstrip().startswith(("{", "[")):
                raise ValueError("ACM case input must be plain stdin text, not JSON object or array")
        return self

    @field_validator("referenceSolutions")
    @classmethod
    def bound_reference_sources(cls, value: dict[str, str]) -> dict[str, str]:
        expected = {f"{language}:{mode}" for language in ("java", "python", "cpp")
                    for mode in ("ACM", "FUNCTION")}
        if set(value) != expected or any(not isinstance(source, str) or not source.strip() or len(source) > 50000 for source in value.values()):
            raise ValueError("invalid reference source set")
        return value


class PrivateGenerationResponse(BaseModel):
    """Explicit refusal is a valid result, so unsupported goals are never silently substituted."""
    draft: PrivateProblemDraft | None = None
    cannotGenerateReason: str = Field(default="", max_length=1000)
    @model_validator(mode="after")
    def require_exactly_one_result(self):
        if (self.draft is None) == (not self.cannotGenerateReason.strip()):
            raise ValueError("return either a complete draft or a clear clarification/unsupported reason")
        return self


class CanonicalSignature(BaseModel):
    model_config = ConfigDict(extra="forbid")
    methodName: str = Field(min_length=1, max_length=80, pattern=r"^[A-Za-z_][A-Za-z0-9_]*$")
    parameters: list[GeneratedParameter] = Field(min_length=1, max_length=8)
    returnType: GeneratedValueType
    dataStructures:list[CustomNodeDefinition]=Field(default_factory=list,max_length=4)
    @model_validator(mode='after')
    def declared_nodes(self):
        from nemo_agent.custom_structures import validate_signature
        validate_signature(self.model_dump());return self


class CanonicalPrivateDraft(BaseModel):
    """The model supplies inputs and algorithms, never expected answers or IO wrappers."""
    model_config = ConfigDict(extra="forbid")
    title: str = Field(min_length=3, max_length=120)
    tags: list[str] = Field(min_length=1, max_length=8)
    difficulty: Literal["EASY", "MEDIUM", "HARD"]
    contentMarkdown: str = Field(min_length=100, max_length=16000)
    signature: CanonicalSignature
    inputs: list[str] = Field(min_length=20, max_length=20, description="20 JSON objects keyed by parameter name; inputs only, no answers")
    pythonSolution: str = Field(min_length=20, max_length=24000)
    pythonOracle: str = Field(min_length=20, max_length=24000, description="Independent small-input brute-force Solution, not a copy of the optimized algorithm")
    javaSolution: str = Field(min_length=20, max_length=24000)
    cppSolution: str = Field(min_length=20, max_length=24000)
    solutionStrategy: str = Field(min_length=12, max_length=600)
    oracleStrategy: str = Field(min_length=12, max_length=600)

    @model_validator(mode="after")
    def independent_sources(self):
        if self.pythonSolution.strip() == self.pythonOracle.strip():
            raise ValueError("oracle must be an independent implementation")
        if self.solutionStrategy.strip() == self.oracleStrategy.strip():
            raise ValueError("explain different optimized and brute-force approaches")
        return self


class CanonicalGenerationResponse(BaseModel):
    draft: CanonicalPrivateDraft | None = None
    cannotGenerateReason: str = Field(default="", max_length=1000)

    @field_validator('draft', mode='before')
    @classmethod
    def decode_provider_json_draft(cls, value):
        # Apply the same compatible-object decoding to the existing generation path.
        return decode_provider_object(value,600000)

    @model_validator(mode="after")
    def one_result(self):
        if (self.draft is None) == (not self.cannotGenerateReason.strip()):
            raise ValueError("provide a draft or a clarification reason")
        return self


class ReferencePatch(BaseModel):
    """No field capable of changing the statement, signature, inputs or answers."""
    model_config = ConfigDict(extra="forbid")
    target: Literal["oracle", "python:FUNCTION", "java:FUNCTION", "cpp:FUNCTION"]
    source: str = Field(min_length=20, max_length=24000)


class PrivateSpecification(BaseModel):
    """Freeze the contract before requesting executable implementations."""
    model_config = ConfigDict(extra="forbid")
    title: str = Field(min_length=3, max_length=120)
    tags: list[str] = Field(min_length=1, max_length=8)
    difficulty: Literal["EASY", "MEDIUM", "HARD"]
    contentMarkdown: str = Field(min_length=100, max_length=16000)
    signature: CanonicalSignature
    inputs: list[str] = Field(min_length=20, max_length=100, description='Prefer exactly 20 inputs; the platform selects 20 while preserving the first two public examples')
    solutionStrategy: str = Field(min_length=12, max_length=600)
    oracleStrategy: str = Field(min_length=12, max_length=600)


class PrivateSpecificationResponse(BaseModel):
    specification: PrivateSpecification | None = None
    cannotGenerateReason: str = Field(default="", max_length=1000)

    @field_validator('specification', mode='before')
    @classmethod
    def decode_provider_json_object(cls, value):
        # Some compatible providers serialize nested tool objects as a JSON string.
        # Decode once, then run every normal field/contract validation unchanged.
        return decode_provider_object(value,160000)

    @model_validator(mode="after")
    def one_result(self):
        if (self.specification is None) == (not self.cannotGenerateReason.strip()):
            raise ValueError("provide a specification or an unsupported reason")
        return self


class PrivatePythonAlgorithms(BaseModel):
    model_config = ConfigDict(extra="forbid")
    pythonSolution: str = Field(min_length=20, max_length=24000)
    pythonOracle: str = Field(min_length=20, max_length=24000)

    @model_validator(mode="after")
    def independent(self):
        if self.pythonSolution.strip() == self.pythonOracle.strip():
            raise ValueError("independent oracle required")
        return self


class PrivateImplementations(PrivatePythonAlgorithms):
    javaSolution: str = Field(min_length=20, max_length=24000)
    cppSolution: str = Field(min_length=20, max_length=24000)


class PythonSpecification(PrivateSpecification, PrivatePythonAlgorithms):
    """One call freezes the contract and supplies two independent Python algorithms."""


class PythonSpecificationResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")
    specification: PythonSpecification | None = None
    cannotGenerateReason: str = Field(default="", max_length=400)

    @field_validator('specification', mode='before')
    @classmethod
    def decode_nested_provider_json(cls, value): return decode_provider_object(value,160000)

    @model_validator(mode='after')
    def one_result(self):
        if (self.specification is None) == (not self.cannotGenerateReason.strip()):
            raise ValueError('provide a specification or an unsupported reason')
        return self


class CrossLanguageImplementations(BaseModel):
    model_config = ConfigDict(extra="forbid")
    javaSolution: str = Field(min_length=20, max_length=24000)
    cppSolution: str = Field(min_length=20, max_length=24000)


class TemplateSelection(BaseModel):
    """Select an audited contract, without permission to change algorithms or constraints."""
    model_config = ConfigDict(extra="forbid")
    templateId: Literal["prefix_single", "prefix_queries", "prefix_matrix", "binary_first", "binary_bounds", "binary_matrix_k", "string_unique", "string_distinct", "string_window", "dp_climb", "dp_coins", "dp_edit", "list_reverse", "list_kth", "list_groups", "tree_depth", "tree_levels", "tree_path"] | None
    contextLabel: str = Field(default="", max_length=30)
    reason: str = Field(min_length=3, max_length=300)


SiteHelpKey = Literal['problem_browse','problem_search','submit_flow','submission_modes','language_switch',
    'result_refresh','submission_history','error_details','editor_draft','editor_tools','ai_analysis',
    'study_plan','private_problems','vip_status','login','registration','feedback','limits']


class SiteHelpDecision(BaseModel):
    model_config = ConfigDict(extra='forbid')
    kind: Literal['HELP','CODE_REDIRECT','UNSUPPORTED']
    sections: list[SiteHelpKey] = Field(default_factory=list,max_length=3)

    @model_validator(mode='after')
    def valid_selection(self):
        if (self.kind=='HELP') != bool(self.sections):
            raise ValueError('only HELP selects approved website usage sections')
        return self


class FeedbackDecision(BaseModel):
    canResolve: bool
    faqKey: Literal["submission_refresh", "submission_history", "editor_draft", "account_access"] | None = None
    escalationReason: str = Field(default="", max_length=1000)

    @model_validator(mode="after")
    def constrain_decision(self):
        if self.canResolve != (self.faqKey is not None):
            raise ValueError("only a selected verified FAQ can be resolved automatically")
        return self


class MaintenanceProposal(BaseModel):
    """Human-reviewed editorial proposal; this schema cannot alter judge data."""
    model_config = ConfigDict(extra="forbid")
    hasSuggestion: bool
    summary: str = Field(min_length=8, max_length=1200)
    proposedTitle: str | None = Field(default=None, min_length=3, max_length=80)
    proposedContentMarkdown: str | None = Field(default=None, min_length=20, max_length=8192)
    evidence: list[str] = Field(default_factory=list, max_length=8)
    uncertainty: str = Field(default="", max_length=1200)

    @model_validator(mode="after")
    def require_complete_editorial_copy(self):
        has_copy = self.proposedTitle is not None or self.proposedContentMarkdown is not None
        if self.hasSuggestion and (self.proposedTitle is None or self.proposedContentMarkdown is None):
            raise ValueError("suggestions must include both proposed title and statement")
        if not self.hasSuggestion and has_copy:
            raise ValueError("a no-change inspection cannot contain proposed copy")
        return self

    @field_validator("evidence")
    @classmethod
    def bound_evidence_items(cls, value: list[str]) -> list[str]:
        if any(len(item) > 1200 for item in value):
            raise ValueError("an evidence item is too large")
        return value
