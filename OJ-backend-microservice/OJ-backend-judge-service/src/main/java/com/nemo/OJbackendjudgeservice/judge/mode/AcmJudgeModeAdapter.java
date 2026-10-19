package com.nemo.OJbackendjudgeservice.judge.mode;

import com.nemo.OJbackendmodel.model.codesandbox.ExecuteCodeResponseV2;
import com.nemo.OJbackendmodel.model.dto.question.JudgeCase;
import com.nemo.OJbackendmodel.model.dto.question.JudgeConfig;
import com.nemo.OJbackendmodel.model.entity.Question;
import com.nemo.OJbackendmodel.model.entity.QuestionSubmit;
import com.nemo.OJbackendmodel.model.enums.ExecutionModeEnum;
import com.nemo.OJbackendmodel.model.enums.JudgeInfoMessageEnum;
import com.nemo.OJbackendmodel.model.execution.CaseExecutionResult;
import com.nemo.OJbackendmodel.model.execution.ExecutionCaseInput;
import com.nemo.OJbackendmodel.model.execution.ExecutionPlan;
import com.nemo.OJbackendmodel.model.execution.ResourceLimit;
import com.nemo.OJbackendmodel.model.execution.SourceFile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ACM 模式适配器：用户提交完整程序（Main.java），通过 stdin 读取输入，stdout 输出结果。
 */
@Component
public class AcmJudgeModeAdapter implements JudgeModeAdapter {

    private static final String MAIN_CLASS = "Main.java";

    @Override
    public ExecutionPlan buildExecutionPlan(Question question, QuestionSubmit submit) {
        // 源码文件：用户代码包装为 Main.java
        String fileName = switch (submit.getLanguage()) {
            case "java" -> "Main.java";
            case "python" -> "main.py";
            case "cpp" -> "main.cpp";
            default -> throw new IllegalArgumentException("Unsupported language");
        };
        String entryPoint = switch (submit.getLanguage()) {
            case "java" -> "Main";
            case "python" -> "main.py";
            default -> "main";
        };
        SourceFile mainFile = new SourceFile(fileName, submit.getCode());
        List<SourceFile> sourceFiles = Collections.singletonList(mainFile);

        // 测试用例输入：从 JudgeCase 提取 input，作为 stdin
        List<JudgeCase> judgeCases = question.getJudgeCase();
        List<ExecutionCaseInput> caseInputs = new ArrayList<>();
        if (judgeCases != null) {
            int idx = 0;
            for (JudgeCase jc : judgeCases) {
                caseInputs.add(new ExecutionCaseInput(idx++, jc.getInput()));
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
                .mode(ExecutionModeEnum.ACM)
                .entryPoint(entryPoint)
                .sourceFiles(sourceFiles)
                .cases(caseInputs)
                .limits(limits)
                .build();
    }

    @Override
    public String interpretResult(Question question, ExecuteCodeResponseV2 sandboxResp) {
        if (sandboxResp == null) {
            return JudgeInfoMessageEnum.SYSTEM_ERROR.getValue();
        }

        // 编译错误
        if (sandboxResp.getCompileMessage() != null) {
            return JudgeInfoMessageEnum.COMPILE_ERROR.getValue();
        }

        // 系统错误
        if (sandboxResp.getSystemMessage() != null) {
            return JudgeInfoMessageEnum.SYSTEM_ERROR.getValue();
        }

        List<JudgeCase> judgeCases = question.getJudgeCase();
        if (judgeCases == null) {
            return JudgeInfoMessageEnum.SYSTEM_ERROR.getValue();
        }

        // 沙箱逐用例状态映射
        if (sandboxResp.getCases() != null) {
            for (int i = 0; i < sandboxResp.getCases().size(); i++) {
                CaseExecutionResult cr = sandboxResp.getCases().get(i);
                int status = cr.getStatus() != null ? cr.getStatus() : 0;

                if (status == 1) return JudgeInfoMessageEnum.COMPILE_ERROR.getValue();
                if (status == 2) return JudgeInfoMessageEnum.RUNTIME_ERROR.getValue();
                if (status == 3) return JudgeInfoMessageEnum.TIME_LIMIT_EXCEEDED.getValue();
                if (status == 4) return JudgeInfoMessageEnum.MEMORY_LIMIT_EXCEEDED.getValue();
                if (status == 5) return JudgeInfoMessageEnum.OUTPUT_LIMIT_EXCEEDED.getValue();
                if (status < 0) return JudgeInfoMessageEnum.SYSTEM_ERROR.getValue();
            }
        }

        // 逐用例比较输出（ACM 模式：文本比较）
        if (sandboxResp.getCases() != null) {
            for (int i = 0; i < sandboxResp.getCases().size() && i < judgeCases.size(); i++) {
                String actual = sandboxResp.getCases().get(i).getStdout();
                String expected = judgeCases.get(i).getOutput();
                if (!normalizeText(actual).equals(normalizeText(expected))) {
                    return JudgeInfoMessageEnum.WRONG_ANSWER.getValue();
                }
            }
        }

        return JudgeInfoMessageEnum.ACCEPTED.getValue();
    }

    /**
     * ACM 文本规范化：统一换行符、去行尾空格、去末尾空行
     */
    private String normalizeText(String s) {
        if (s == null) return "";
        // 统一 \r\n → \n
        String normalized = s.replace("\r\n", "\n").replace('\r', '\n');
        // 逐行去行尾空格
        StringBuilder sb = new StringBuilder();
        for (String line : normalized.split("\n")) {
            sb.append(line.replaceAll("\\s+$", "")).append('\n');
        }
        // 去除末尾多余空行
        return sb.toString().replaceAll("\\n+$", "");
    }
}
