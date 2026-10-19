package com.nemo.OJbackendjudgeservice.judge.strategy;

import com.nemo.OJbackendjudgeservice.judge.strategy.DefaultJudgeStrategy;
import com.nemo.OJbackendjudgeservice.judge.strategy.JudgeContext;
import com.nemo.OJbackendmodel.model.codesandbox.JudgeInfo;
import com.nemo.OJbackendmodel.model.dto.question.JudgeCase;
import com.nemo.OJbackendmodel.model.dto.question.JudgeConfig;
import com.nemo.OJbackendmodel.model.entity.Question;
import com.nemo.OJbackendmodel.model.entity.QuestionSubmit;
import com.nemo.OJbackendmodel.model.enums.JudgeInfoMessageEnum;
import com.nemo.OJbackendmodel.model.enums.QuestionSubmitStatusEnum;
import com.nemo.OJserviceclient.QuestionFeignClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JudgeServiceImpl 业务逻辑测试
 */
class DefaultJudgeStrategyTest {

    private DefaultJudgeStrategy judgeStrategy;

    @BeforeEach
    void setUp() {
        judgeStrategy = new DefaultJudgeStrategy();
    }

    @Test void effectivePythonBudgetIsUsedWithoutScalingMeasuredTime() {
        JudgeContext ctx=buildContext(List.of("1"),List.of("1"),List.of(createCase("1","1")),262144L,1000L);
        setSandboxResult(ctx,2000L,1024L);
        ctx.setEffectiveLimits(com.nemo.OJbackendmodel.model.execution.ResourceLimit.builder().timeLimitMs(3000L).memoryLimitKB(262144L).build());
        var result=judgeStrategy.doJudgeWithChecker(ctx,new com.nemo.OJbackendjudgeservice.judge.checker.ExactChecker(),null);
        assertEquals(JudgeInfoMessageEnum.ACCEPTED.getValue(),result.getMessage());
        assertEquals(2000L,result.getExecutedTime());
        ctx.getEffectiveLimits().setTimeLimitMs(1500L);
        assertEquals(JudgeInfoMessageEnum.TIME_LIMIT_EXCEEDED.getValue(),judgeStrategy.doJudgeWithChecker(ctx,new com.nemo.OJbackendjudgeservice.judge.checker.ExactChecker(),null).getMessage());
    }
    @Test
    void testAcceptedResult() {
        JudgeContext ctx = buildContext(
                Arrays.asList("1 2", "3 4"),  // inputs
                Arrays.asList("5", "11"),       // outputs
                Arrays.asList(createCase("1 2", "5"), createCase("3 4", "11")),
                256000L,  // memory limit KB
                5000L     // time limit ms
        );
        setSandboxResult(ctx, 100L, 1000L); // 100ms, 1000KB

        JudgeInfo result = judgeStrategy.doJudge(ctx);
        assertEquals(JudgeInfoMessageEnum.ACCEPTED.getValue(), result.getMessage());
    }

    @Test
    void testWrongAnswer() {
        JudgeContext ctx = buildContext(
                Collections.singletonList("1"),
                Collections.singletonList("wrong_output"),
                Collections.singletonList(createCase("1", "expected_output")),
                256000L, 5000L
        );
        setSandboxResult(ctx, 100L, 1000L);

        JudgeInfo result = judgeStrategy.doJudge(ctx);
        assertEquals(JudgeInfoMessageEnum.WRONG_ANSWER.getValue(), result.getMessage());
    }

    @Test
    void testMemoryLimitExceeded() {
        JudgeContext ctx = buildContext(
                Collections.singletonList("1"),
                Collections.singletonList("1"),
                Collections.singletonList(createCase("1", "1")),
                500L,    // 500KB limit
                10000L
        );
        setSandboxResult(ctx, 200L, 10000L); // 10000KB > 500KB

        JudgeInfo result = judgeStrategy.doJudge(ctx);
        assertEquals(JudgeInfoMessageEnum.MEMORY_LIMIT_EXCEEDED.getValue(), result.getMessage());
    }

    @Test
    void testTimeLimitExceeded() {
        JudgeContext ctx = buildContext(
                Collections.singletonList("1"),
                Collections.singletonList("1"),
                Collections.singletonList(createCase("1", "1")),
                256000L,
                200L     // 200ms limit
        );
        // 实际耗时 8000ms，远超 200ms + 5000ms Java 补偿
        setSandboxResult(ctx, 8000L, 100L);

        JudgeInfo result = judgeStrategy.doJudge(ctx);
        assertEquals(JudgeInfoMessageEnum.TIME_LIMIT_EXCEEDED.getValue(), result.getMessage());
    }

    @Test
    void testOutputCountMismatch() {
        JudgeContext ctx = buildContext(
                Arrays.asList("1", "2"),        // 2 inputs
                Collections.singletonList("1"),  // 1 output (mismatch!)
                Arrays.asList(createCase("1", "1"), createCase("2", "2")),
                256000L, 5000L
        );
        setSandboxResult(ctx, 100L, 100L);

        JudgeInfo result = judgeStrategy.doJudge(ctx);
        assertEquals(JudgeInfoMessageEnum.WRONG_ANSWER.getValue(), result.getMessage());
    }

    @Test
    void testNullMemoryAndTimeHandled() {
        // JudgeInfo fields null → default to 0, should not NPE
        JudgeContext ctx = buildContext(
                Collections.singletonList("1"),
                Collections.singletonList("1"),
                Collections.singletonList(createCase("1", "1")),
                256000L, 5000L
        );
        // memory and time both null
        JudgeInfo sandboxInfo = new JudgeInfo();
        ctx.setJudgeInfo(sandboxInfo);

        JudgeInfo result = judgeStrategy.doJudge(ctx);
        assertNotNull(result);
        assertEquals(0L, result.getExecutedMemory());
        assertEquals(0L, result.getExecutedTime());
        assertEquals(JudgeInfoMessageEnum.ACCEPTED.getValue(), result.getMessage());
    }

    // --- helpers ---

    private JudgeContext buildContext(List<String> inputs, List<String> outputs,
                                      List<JudgeCase> cases, Long memLimitKB, Long timeLimitMs) {
        JudgeContext ctx = new JudgeContext();
        ctx.setInputList(inputs);
        ctx.setOutputList(outputs);
        ctx.setJudgeCaseList(cases);

        Question q = new Question();
        q.setJudgeCase(cases);
        JudgeConfig config = new JudgeConfig();
        config.setMemoryLimit(memLimitKB);
        config.setTimeLimit(timeLimitMs);
        q.setJudgeConfig(config);
        ctx.setQuestion(q);
        ctx.setQuestionSubmit(new QuestionSubmit());
        return ctx;
    }

    private void setSandboxResult(JudgeContext ctx, long timeMs, long memoryKB) {
        JudgeInfo info = ctx.getJudgeInfo();
        if (info == null) {
            info = new JudgeInfo();
            ctx.setJudgeInfo(info);
        }
        info.setExecutedTime(timeMs);
        info.setExecutedMemory(memoryKB);
    }

    private JudgeCase createCase(String input, String output) {
        JudgeCase jc = new JudgeCase();
        jc.setInput(input);
        jc.setOutput(output);
        return jc;
    }
}
