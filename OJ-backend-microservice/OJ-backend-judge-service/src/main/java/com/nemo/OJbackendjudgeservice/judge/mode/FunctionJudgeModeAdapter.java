package com.nemo.OJbackendjudgeservice.judge.mode;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.nemo.OJbackendjudgeservice.judge.harness.JavaHarnessGenerator;
import com.nemo.OJbackendmodel.model.codesandbox.ExecuteCodeResponseV2;
import com.nemo.OJbackendmodel.model.codesandbox.JudgeInfo;
import com.nemo.OJbackendmodel.model.dto.question.JudgeCase;
import com.nemo.OJbackendmodel.model.dto.question.JudgeConfig;
import com.nemo.OJbackendmodel.model.dto.question.ProblemSpec;
import com.nemo.OJbackendmodel.model.entity.Question;
import com.nemo.OJbackendmodel.model.entity.QuestionSubmit;
import com.nemo.OJbackendmodel.model.enums.ExecutionModeEnum;
import com.nemo.OJbackendmodel.model.enums.JudgeInfoMessageEnum;
import com.nemo.OJbackendmodel.model.execution.CaseExecutionResult;
import com.nemo.OJbackendmodel.model.execution.ExecutionCaseInput;
import com.nemo.OJbackendmodel.model.execution.ExecutionPlan;
import com.nemo.OJbackendmodel.model.execution.ResourceLimit;
import com.nemo.OJbackendmodel.model.execution.SourceFile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * 核心代码模式适配器：用户只实现指定函数，系统生成 Runner 调用。
 */
@Slf4j
@Component
public class FunctionJudgeModeAdapter implements JudgeModeAdapter {

    @Resource
    private com.nemo.OJbackendjudgeservice.judge.harness.HarnessGeneratorRegistry harnessRegistry;

    private static final Gson gson = new Gson();

    private static final String SOLUTION_FILE = "Solution.java"; // 与校验层强制 className=Solution 一致
    private static final String RUNNER_FILE = "Runner.java";
    private static final String LIST_NODE_FILE = "ListNode.java";
    private static final String TREE_NODE_FILE = "TreeNode.java";

    @Override
    public ExecutionPlan buildExecutionPlan(Question question, QuestionSubmit submit) {
        ProblemSpec spec = question.getProblemSpec();

        List<SourceFile> sourceFiles = new ArrayList<>();

        String entryPoint = "Runner";
        boolean custom=com.nemo.OJbackendjudgeservice.judge.harness.CustomNodeGenerator.enabled(spec);
        if ("java".equals(submit.getLanguage())) {
        // 1. 用户代码——文件名与 className 一致，保证 javac 编译通过
        String userFileName = spec.getClassName() + ".java";
        sourceFiles.add(new SourceFile(userFileName,
                com.nemo.OJbackendjudgeservice.judge.harness.FunctionCodePrelude.javaSource(submit.getCode())));

        // 2. 生成 Runner.java
        String runnerCode = harnessRegistry.get(submit.getLanguage()).generate(spec);
        if(custom)runnerCode+="\n"+com.nemo.OJbackendjudgeservice.judge.harness.CustomNodeGenerator.javaSource(spec).replaceAll("(?m)^import[^;]+;\\s*","");
        sourceFiles.add(new SourceFile(RUNNER_FILE, runnerCode));

        // 3. 可信 SDK 文件
        sourceFiles.add(new SourceFile(LIST_NODE_FILE, getListNodeSource()));
        sourceFiles.add(new SourceFile(TREE_NODE_FILE, getTreeNodeSource()));

        } else if ("python".equals(submit.getLanguage())) {
            entryPoint = "runner.py";
            sourceFiles.add(new SourceFile("solution.py", submit.getCode()));
            sourceFiles.add(new SourceFile("runner.py", harnessRegistry.get("python").generate(spec)));
        } else if ("cpp".equals(submit.getLanguage())) {
            entryPoint = "main";
            sourceFiles.add(new SourceFile("solution.hpp", submit.getCode()));
            sourceFiles.add(new SourceFile("runner.cpp", harnessRegistry.get("cpp").generate(spec)));
            sourceFiles.add(new SourceFile("nodes.hpp", com.nemo.OJbackendjudgeservice.judge.harness.HarnessGeneratorRegistry.resource("nodes.hpp")));
        } else { throw new IllegalArgumentException("Unsupported language"); }
        // 4. 构建 JSON 输入（每个用例的 params 作为 stdin）
        List<JudgeCase> judgeCases = functionJudgeCases(question);
        List<ExecutionCaseInput> caseInputs = new ArrayList<>();
        if (judgeCases != null) {
            for (int i = 0; i < judgeCases.size(); i++) {
                String stdin = judgeCases.get(i).getInput();
                caseInputs.add(new ExecutionCaseInput(i, stdin));
            }
        }

        // 资源限制
        JudgeConfig config = question.getJudgeConfig();
        ResourceLimit limits = ResourceLimit.builder()
                .timeLimitMs(config != null ? config.getTimeLimit() : null)
                .memoryLimitKB(config != null ? config.getMemoryLimit() : null)
                .outputLimitBytes(config != null ? config.getOutputLimitBytes() : null)
                .build();

        return ExecutionPlan.builder()
                .language(submit.getLanguage())
                .mode(ExecutionModeEnum.FUNCTION)
                .entryPoint(entryPoint)
                .sourceFiles(sourceFiles)
                .cases(caseInputs)
                .limits(limits)
                .harnessVersion(spec.getHarnessVersion() != null
                        ? spec.getHarnessVersion().toString() : "1")
                .build();
    }

    @Override
    public String interpretResult(Question question, ExecuteCodeResponseV2 sandboxResp) {
        if (sandboxResp == null) {
            return JudgeInfoMessageEnum.SYSTEM_ERROR.getValue();
        }

        if (sandboxResp.getCompileMessage() != null) {
            return JudgeInfoMessageEnum.COMPILE_ERROR.getValue();
        }

        if (sandboxResp.getSystemMessage() != null) {
            return JudgeInfoMessageEnum.SYSTEM_ERROR.getValue();
        }

        // 检查用例状态
        if (sandboxResp.getCases() != null) {
            for (CaseExecutionResult cr : sandboxResp.getCases()) {
                int status = cr.getStatus() != null ? cr.getStatus() : 0;
                if (status == 1) return JudgeInfoMessageEnum.COMPILE_ERROR.getValue();
                if (status == 2) return JudgeInfoMessageEnum.RUNTIME_ERROR.getValue();
                if (status == 3) return JudgeInfoMessageEnum.TIME_LIMIT_EXCEEDED.getValue();
                if (status == 4) return JudgeInfoMessageEnum.MEMORY_LIMIT_EXCEEDED.getValue();
                if (status == 5) return JudgeInfoMessageEnum.OUTPUT_LIMIT_EXCEEDED.getValue();
                if (status < 0) return JudgeInfoMessageEnum.SYSTEM_ERROR.getValue();
            }
        }

        // 解析 Runner 协议输出并比较
        List<JudgeCase> judgeCases = functionJudgeCases(question);
        if (sandboxResp.getCases() != null && judgeCases != null) {
            for (int i = 0; i < sandboxResp.getCases().size() && i < judgeCases.size(); i++) {
                String stdout = sandboxResp.getCases().get(i).getStdout();
                String expected = judgeCases.get(i).getOutput();
                if (!compareStructured(stdout, expected)) {
                    return JudgeInfoMessageEnum.WRONG_ANSWER.getValue();
                }
            }
        }

        return JudgeInfoMessageEnum.ACCEPTED.getValue();
    }

    /**
     * 结构化比较：解析 Runner 输出的单行 JSON，提取 "actual" 字段与期望比较。
     * Runner 协议输出格式：{"status":"OK","actual":...,"timeMs":N}
     */
    private boolean compareStructured(String runnerOutput, String expected) {
        if (runnerOutput == null && expected == null) return true;
        if (runnerOutput == null || expected == null) return false;
        try {
            // Runner 输出是单个 JSON 对象
            JsonObject runnerResult = gson.fromJson(runnerOutput, JsonObject.class);
            if (runnerResult == null) return false;

            String status = runnerResult.get("status").getAsString();
            if (!"OK".equals(status)) return false;

            // 获取 actual 字段
            JsonElement actual = runnerResult.get("actual");
            if (actual == null) return "null".equals(expected) || expected == null;

            // 将 expected 解析为 JsonElement 后深度比较
            JsonElement expectedElem = gson.fromJson(expected, JsonElement.class);
            return actual.equals(expectedElem);
        } catch (Exception e) {
            log.error("结构化比较异常, runnerOutput={}, expected={}", runnerOutput, expected, e);
            return false;
        }
    }

    /**
     * 提取执行信息（最大时间、最大内存）
     */
    public JudgeInfo extractJudgeInfo(ExecuteCodeResponseV2 sandboxResp) {
        JudgeInfo info = new JudgeInfo();
        if (sandboxResp.getCases() != null) {
            long maxTime = 0, maxMem = 0;
            for (CaseExecutionResult cr : sandboxResp.getCases()) {
                if (cr.getTime() != null) maxTime = Math.max(maxTime, cr.getTime());
                if (cr.getMemory() != null) maxMem = Math.max(maxMem, cr.getMemory());
            }
            info.setExecutedTime(maxTime);
            info.setExecutedMemory(maxMem);
        }
        return info;
    }

    private List<JudgeCase> functionJudgeCases(Question question) {
        ProblemSpec spec = question.getProblemSpec();
        return spec != null && spec.getJudgeCases() != null && !spec.getJudgeCases().isEmpty()
                ? spec.getJudgeCases() : question.getJudgeCase();
    }

    // ======================== Trusted SDK Sources ========================

    private static final String LIST_NODE_SOURCE = com.nemo.OJbackendjudgeservice.judge.harness.HarnessGeneratorRegistry.resource("ListNode.java");
    private static final String TREE_NODE_SOURCE = com.nemo.OJbackendjudgeservice.judge.harness.HarnessGeneratorRegistry.resource("TreeNode.java");
    private String getListNodeSource() {
        return LIST_NODE_SOURCE;
    }

    private String getTreeNodeSource() {
        return TREE_NODE_SOURCE;
    }
}
