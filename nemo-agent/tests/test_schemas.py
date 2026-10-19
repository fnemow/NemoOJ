import pytest
import asyncio
from pydantic import ValidationError

from nemo_agent.analysis_graph import create_analysis_graph, normalize_plan_items, parse_structured_answer
from nemo_agent.schemas import AnalysisReport, FeedbackDecision, MaintenanceProposal, PlanItemDraft, PlanOutput, PrivateProblemDraft


def test_plan_requires_one_source_and_public_fallback_for_private_item():
    with pytest.raises(ValidationError):
        PlanItemDraft(dayNumber=1, orderNumber=1, privateGoal="数组练习题", reason="练习数组基础算法")
    with pytest.raises(ValidationError):
        PlanItemDraft(dayNumber=1, orderNumber=1, problemNo=1, privateGoal="同时选择两类来源", fallbackProblemNo=2,
                      reason="练习数组基础算法")


def test_mixed_plan_is_limited_to_five_private_items():
    common = {"dayNumber": 1, "orderNumber": 1, "privateGoal": "练习数组算法", "fallbackProblemNo": 9,
              "reason": "为用户目标提供额外的私有练习题。"}
    with pytest.raises(ValidationError):
        PlanOutput(items=[{**common, "dayNumber": day} for day in range(1, 7)])


def test_feedback_auto_reply_must_select_a_known_faq_key():
    assert FeedbackDecision(canResolve=True, faqKey="submission_refresh").canResolve
    assert not FeedbackDecision(canResolve=False, escalationReason="需要人工检查").canResolve
    with pytest.raises(ValidationError):
        FeedbackDecision(canResolve=True, faqKey=None)


def test_structured_output_recovers_valid_decoded_tool_arguments():
    cases = [{"input": f"{i} {i + 1}", "output": str(2 * i + 1), "isHidden": i > 1} for i in range(20)]
    payload = {
        "title": "两数求和练习",
        "tags": ["数学"],
        "difficulty": "EASY",
        "contentMarkdown": "给定两个整数，计算并输出它们的和。输入由一行两个整数构成，输出一个整数作为答案。" * 3,
        "judgeCases": cases,
        "judgeConfig": {"timeLimit": 2000, "memoryLimit": 262144},
        "problemSpec": {
            "mode": "function", "className": "Solution", "methodName": "solve",
            "parameters": [{"name": "a", "type": "int"}, {"name": "b", "type": "int"}],
            "returnType": "int", "resultSource": "return", "comparator": {"type": "EXACT"},
            "harnessVersion": 1,
            "judgeCases": [{"input": f'{{"a":{i},"b":{i + 1}}}', "output": str(2 * i + 1), "isHidden": i > 1}
                           for i in range(20)],
        },
        "referenceSolutions": {
            "java:ACM": "class Main { public static void main(String[] a) {} }",
            "python:ACM": "print(sum(map(int,input().split())))",
            "cpp:ACM": "int main(){return 0;}",
            "java:FUNCTION": "class Solution { int solve(int a, int b) { return a + b; } }",
            "python:FUNCTION": "class Solution:\n    def solve(self, a, b): return a + b",
            "cpp:FUNCTION": "class Solution { public: int solve(int a, int b) { return a + b; } };",
        },
    }

    class Raw:
        tool_calls = [{"name": "PrivateProblemDraft", "args": payload}]
        invalid_tool_calls = []
        response_metadata = {"finish_reason": "tool_calls"}

    parsed = parse_structured_answer({"parsed": None, "raw": Raw(), "parsing_error": RuntimeError("parser")},
                                     PrivateProblemDraft)
    assert parsed.title == "两数求和练习"
    invalid_function = dict(payload)
    invalid_function["problemSpec"] = {**payload["problemSpec"],
        "judgeCases": [{**case, "input": "[1,2]"} for case in payload["problemSpec"]["judgeCases"]]}
    with pytest.raises(ValidationError):
        PrivateProblemDraft.model_validate(invalid_function)
    invalid_acm = dict(payload)
    invalid_acm["judgeCases"] = [{**case, "input": '{"a":1,"b":2}'} for case in payload["judgeCases"]]
    with pytest.raises(ValidationError):
        PrivateProblemDraft.model_validate(invalid_acm)


def test_structured_output_repairs_truncated_tool_arguments_only_after_validation():
    args = ('{"verdictEvidence":{"verdict":"time_limit_exceeded"},'
            '"likelyCause":"The visible run exceeded its time limit",'
            '"locations":[],"suggestions":[],"complexity":{"time":"unknown","space":"unknown"},'
            '"uncertainty":"Input-specific cause is not known')

    class Raw:
        tool_calls = []
        invalid_tool_calls = [{"name": "AnalysisReport", "args": args}]
        response_metadata = {"finish_reason": "tool_calls"}

    report = parse_structured_answer({"parsed": None, "raw": Raw()}, AnalysisReport)
    assert report.verdictEvidence["verdict"] == "time_limit_exceeded"
    assert report.uncertainty.startswith("Input-specific cause")


def test_maintenance_proposal_is_editorial_only_and_requires_complete_copy():
    proposal = MaintenanceProposal(hasSuggestion=True, summary="Clarify the wording used for the input data.",
        proposedTitle="Clear input description", proposedContentMarkdown="Read one integer and return the requested result using the specified output format.",
        evidence=["The first paragraph does not define the input object clearly."])
    assert proposal.hasSuggestion
    assert "judgeCases" not in proposal.model_dump()
    with pytest.raises(ValidationError):
        MaintenanceProposal(hasSuggestion=True, summary="Change the title", proposedTitle="New title")
    with pytest.raises(ValidationError):
        MaintenanceProposal(hasSuggestion=False, summary="This is a safe editorial suggestion", proposedTitle="New title",
                            proposedContentMarkdown="Read one integer and return the requested result using the specified output format.")
    with pytest.raises(ValidationError):
        MaintenanceProposal(hasSuggestion=False, summary="This is a safe editorial suggestion", proposedTitle="New title")


def test_plan_adjustment_drops_model_duplicates_of_completed_items():
    class DuplicateModel:
        def with_structured_output(self, schema, include_raw=False, **kwargs):
            return self

        async def ainvoke(self, messages):
            return {"parsed": PlanOutput(items=[
                PlanItemDraft(dayNumber=1, orderNumber=1, problemNo=1,
                              reason="The user has already finished this item."),
                PlanItemDraft(dayNumber=2, orderNumber=1, problemNo=2,
                              reason="A fresh practice item for the next day."),
            ]), "raw": None}

    graph = create_analysis_graph(DuplicateModel())
    result = asyncio.run(graph.ainvoke({"context": {
        "taskType": "PLAN_ADJUST", "request": {"days": 7},
        "catalog": [{"problemNo": number} for number in range(2, 10)], "completedProblemNos": [1],
        "currentItems": [{"dayNumber": 1, "orderNumber": 1,
                          "problemNo": 1, "status": "DONE"}],
    }}))
    items = result["plan"]["items"]
    assert [item["dayNumber"] for item in items] == list(range(2, 8))
    assert items[0]["problemNo"] == 2
    assert len({item["problemNo"] for item in items}) == 6


def test_plan_normalization_replaces_duplicate_numbers_and_missing_days():
    plan = {"items": [
        {"dayNumber": 1, "orderNumber": 1, "problemNo": 1, "reason": "练习基础数组题目"},
        {"dayNumber": 2, "orderNumber": 1, "problemNo": 1, "reason": "重复使用第一天题目"},
    ]}
    result = normalize_plan_items(plan, {"request": {"days": 3, "sourceMode": "CATALOG"},
        "catalog": [{"problemNo": number} for number in range(1, 8)]})
    assert [item["dayNumber"] for item in result["items"]] == [1, 2, 3]
    assert [item["problemNo"] for item in result["items"]] == [1, 2, 3]


def test_inspection_prompt_excludes_hidden_cases_and_reference_solutions():
    class CaptureModel:
        def with_structured_output(self, schema, include_raw=False, **kwargs):
            self.schema = schema
            return self

        async def ainvoke(self, messages):
            prompt = messages[-1].content
            assert "hiddenCases" not in prompt
            assert "referenceSolutions" not in prompt
            assert "Visible problem statement" in prompt
            return {"parsed": MaintenanceProposal(hasSuggestion=False,
                    summary="The visible problem statement is clear.",
                    evidence=["The statement defines its requested input."]), "raw": None}

    graph = create_analysis_graph(CaptureModel())
    result = asyncio.run(graph.ainvoke({"context": {"taskType": "INSPECTION", "inspectionProblem": {
        "problemNo": 7, "title": "Visible title", "content": "Visible problem statement", "tags": ["array"],
        "hiddenCases": ["private testcase"], "referenceSolutions": {"secret": "reference code"},
    }}}))
    assert result["maintenance_proposal"]["hasSuggestion"] is False
