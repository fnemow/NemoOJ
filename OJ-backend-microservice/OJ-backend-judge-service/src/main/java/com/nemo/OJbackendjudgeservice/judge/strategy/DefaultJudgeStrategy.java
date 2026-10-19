package com.nemo.OJbackendjudgeservice.judge.strategy;



import com.nemo.OJbackendjudgeservice.judge.checker.CheckResult;
import com.nemo.OJbackendjudgeservice.judge.checker.OutputChecker;
import com.nemo.OJbackendmodel.model.codesandbox.JudgeInfo;
import com.nemo.OJbackendmodel.model.dto.question.JudgeCase;
import com.nemo.OJbackendmodel.model.dto.question.JudgeConfig;
import com.nemo.OJbackendmodel.model.entity.Question;
import com.nemo.OJbackendmodel.model.enums.JudgeInfoMessageEnum;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 默认判题策略：支持 V1 字符串比较和 V2 比较器比较。
 */
public class DefaultJudgeStrategy implements JudgeStrategy {
    @Override
    public JudgeInfo doJudge(JudgeContext judgeContext) {
        JudgeInfo judgeInfo = judgeContext.getJudgeInfo();
        Long executedMemory = Optional.ofNullable(judgeInfo.getExecutedMemory()).orElse(0L);
        Long executedTime = Optional.ofNullable(judgeInfo.getExecutedTime()).orElse(0L);
        List<String> inputList = judgeContext.getInputList();
        List<String> outputList = judgeContext.getOutputList();
        Question question = judgeContext.getQuestion();
        List<JudgeCase> judgeCaseList = question.getJudgeCase();
        JudgeInfoMessageEnum judgeInfoMessageEnum = JudgeInfoMessageEnum.ACCEPTED;
        JudgeInfo judgeInfoResponse = new JudgeInfo();
        judgeInfoResponse.setExecutedMemory(executedMemory);
        judgeInfoResponse.setExecutedTime(executedTime);
        //先判断沙箱执行结果输出数量是否和预期输出数量相等
        if(outputList.size() != inputList.size()) {
            judgeInfoMessageEnum = JudgeInfoMessageEnum.WRONG_ANSWER;
            judgeInfoResponse.setMessage(judgeInfoMessageEnum.getValue());
            return judgeInfoResponse;
        }
        //依次判断每一项输出和预期输出是否相等
        for(int i = 0; i < judgeCaseList.size(); i++) {
            JudgeCase judgeCase = judgeCaseList.get(i);
            if(!judgeCase.getOutput().equals(outputList.get(i))) {
                judgeInfoMessageEnum = JudgeInfoMessageEnum.WRONG_ANSWER;
                judgeInfoResponse.setMessage(judgeInfoMessageEnum.getValue());
                return judgeInfoResponse;
            }
        }
        //判断题目限制（沙箱已按题目时间限制执行，不再额外补偿）
        JudgeConfig judgeConfig = question.getJudgeConfig();
        Long needMemoryLimit = judgeConfig.getMemoryLimit();
        Long needTimeLimit = judgeConfig.getTimeLimit();
        if (needMemoryLimit != null && executedMemory > needMemoryLimit) {
            judgeInfoMessageEnum = JudgeInfoMessageEnum.MEMORY_LIMIT_EXCEEDED;
            judgeInfoResponse.setMessage(judgeInfoMessageEnum.getValue());
            return judgeInfoResponse;
        }
        if (needTimeLimit != null && executedTime > needTimeLimit) {
            judgeInfoMessageEnum = JudgeInfoMessageEnum.TIME_LIMIT_EXCEEDED;
            judgeInfoResponse.setMessage(judgeInfoMessageEnum.getValue());
            return judgeInfoResponse;
        }
        judgeInfoResponse.setMessage(judgeInfoMessageEnum.getValue());
        return judgeInfoResponse;
    }

    /**
     * V2 判题：使用 OutputChecker 进行逐用例比较。
     */
    public JudgeInfo doJudgeWithChecker(JudgeContext judgeContext, OutputChecker checker,
                                         Map<String, Object> checkerConfig) {
        JudgeInfo judgeInfo = judgeContext.getJudgeInfo();
        Long executedMemory = Optional.ofNullable(judgeInfo.getExecutedMemory()).orElse(0L);
        Long executedTime = Optional.ofNullable(judgeInfo.getExecutedTime()).orElse(0L);
        List<String> outputList = judgeContext.getOutputList();
        List<JudgeCase> judgeCaseList = judgeContext.getJudgeCaseList();
        if (judgeCaseList == null) {
            judgeCaseList = judgeContext.getQuestion().getJudgeCase();
        }

        JudgeInfo judgeInfoResponse = new JudgeInfo();
        judgeInfoResponse.setExecutedMemory(executedMemory);
        judgeInfoResponse.setExecutedTime(executedTime);

        // 校验输出数量
        if (outputList == null || judgeCaseList == null
                || outputList.size() != judgeCaseList.size()) {
            judgeInfoResponse.setMessage(JudgeInfoMessageEnum.WRONG_ANSWER.getValue());
            return judgeInfoResponse;
        }

        // 逐用例使用 checker 比较
        for (int i = 0; i < judgeCaseList.size(); i++) {
            JudgeCase judgeCase = judgeCaseList.get(i);
            String expected = judgeCase.getOutput();
            String actual = outputList.get(i);
            CheckResult cr = checker.check(expected, actual, checkerConfig);
            if (!cr.isPassed()) {
                judgeInfoResponse.setMessage(JudgeInfoMessageEnum.WRONG_ANSWER.getValue());
                return judgeInfoResponse;
            }
        }

        // 检查资源限制
        Question question = judgeContext.getQuestion();
        if (question != null) {
            JudgeConfig judgeConfig = question.getJudgeConfig();
            if (judgeContext.getEffectiveLimits() != null) {
                judgeConfig = new JudgeConfig();
                judgeConfig.setTimeLimit(judgeContext.getEffectiveLimits().getTimeLimitMs());
                judgeConfig.setMemoryLimit(judgeContext.getEffectiveLimits().getMemoryLimitKB());
            }
            if (judgeConfig != null) {
                if (judgeConfig.getMemoryLimit() != null && executedMemory > judgeConfig.getMemoryLimit()) {
                    judgeInfoResponse.setMessage(JudgeInfoMessageEnum.MEMORY_LIMIT_EXCEEDED.getValue());
                    return judgeInfoResponse;
                }
                if (judgeConfig.getTimeLimit() != null && executedTime > judgeConfig.getTimeLimit()) {
                    judgeInfoResponse.setMessage(JudgeInfoMessageEnum.TIME_LIMIT_EXCEEDED.getValue());
                    return judgeInfoResponse;
                }
            }
        }

        judgeInfoResponse.setMessage(JudgeInfoMessageEnum.ACCEPTED.getValue());
        return judgeInfoResponse;
    }
}
