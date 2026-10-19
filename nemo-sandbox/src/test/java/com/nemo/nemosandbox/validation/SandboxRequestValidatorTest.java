package com.nemo.nemosandbox.validation;

import com.nemo.nemosandbox.model.ExecutionCaseInput;
import com.nemo.nemosandbox.model.ExecutionPlan;
import com.nemo.nemosandbox.model.ResourceLimit;
import com.nemo.nemosandbox.model.SourceFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 沙箱请求自校验测试（T08）。
 * <p>
 * 核心前提：**不完全信任调用方**。这里覆盖的是"即使上层校验被绕过或配置错误，
 * 沙箱也会拒绝越界请求"这一层。
 */
class SandboxRequestValidatorTest {

    private SandboxRequestValidator validator;

    @BeforeEach
    void setUp() {
        validator = new SandboxRequestValidator();
        // 手工注入配置默认值（单元测试不启动 Spring 上下文）
        ReflectionTestUtils.setField(validator, "maxCases", 200);
        ReflectionTestUtils.setField(validator, "maxTotalInputBytes", 4 * 1024 * 1024L);
        ReflectionTestUtils.setField(validator, "maxTimeLimitMs", 60000L);
        ReflectionTestUtils.setField(validator, "minTimeLimitMs", 100L);
        ReflectionTestUtils.setField(validator, "maxMemoryLimitKb", 1048576L);
        ReflectionTestUtils.setField(validator, "minMemoryLimitKb", 16384L);
        ReflectionTestUtils.setField(validator, "maxOutputLimitBytes", 1048576L);
    }

    private static SourceFile file(String name, String content) {
        return new SourceFile(name, content);
    }

    private static ExecutionCaseInput cs(String stdin) {
        ExecutionCaseInput c = new ExecutionCaseInput();
        c.setStdin(stdin);
        return c;
    }

    private ExecutionPlan validPlan() {
        ExecutionPlan plan = new ExecutionPlan();
        plan.setLanguage("java");
        plan.setSourceFiles(new ArrayList<>(Collections.singletonList(file("Main.java", "class Main{}"))));
        plan.setCases(new ArrayList<>(Collections.singletonList(cs("1 2"))));
        plan.setLimits(new ResourceLimit(1000L, 262144L, 65536L));
        return plan;
    }

    // ---------- 正常路径 ----------

    @Test
    @DisplayName("合法请求通过校验")
    void acceptsValidPlan() {
        assertNull(validator.validate(validPlan()), "合法请求应通过");
    }

    // ---------- 结构缺失 ----------

    @Test
    @DisplayName("缺少计划/语言/源文件/用例/资源限制均被拒绝")
    void rejectsMissingStructure() {
        assertNotNull(validator.validate(null));

        ExecutionPlan p = validPlan();
        p.setLanguage("  ");
        assertNotNull(validator.validate(p));

        p = validPlan();
        p.setSourceFiles(Collections.emptyList());
        assertNotNull(validator.validate(p));

        p = validPlan();
        p.setCases(Collections.emptyList());
        assertNotNull(validator.validate(p));

        p = validPlan();
        p.setLimits(null);
        assertNotNull(validator.validate(p));
    }

    @Test
    @DisplayName("空文件名或空内容被拒绝")
    void rejectsBadSourceFiles() {
        ExecutionPlan p = validPlan();
        p.setSourceFiles(new ArrayList<>(Collections.singletonList(file(null, "x"))));
        assertNotNull(validator.validate(p));

        p = validPlan();
        p.setSourceFiles(new ArrayList<>(Collections.singletonList(file("Main.java", null))));
        assertNotNull(validator.validate(p));
    }

    // ---------- 数量与体积 ----------

    @Test
    @DisplayName("用例数超限被拒绝")
    void rejectsTooManyCases() {
        ExecutionPlan p = validPlan();
        List<ExecutionCaseInput> many = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            many.add(cs("1"));
        }
        p.setCases(many);
        String reason = validator.validate(p);
        assertNotNull(reason);
        assertTrue(reason.contains("用例数超限"), reason);
    }

    @Test
    @DisplayName("输入总量超限被拒绝")
    void rejectsOversizedInput() {
        ExecutionPlan p = validPlan();
        p.setCases(new ArrayList<>(Collections.singletonList(cs("x".repeat(5 * 1024 * 1024)))));
        String reason = validator.validate(p);
        assertNotNull(reason);
        assertTrue(reason.contains("输入总量超限"), reason);
    }

    // ---------- 资源参数上下界 ----------

    @Test
    @DisplayName("时间限制越界（过小/过大/缺失）被拒绝")
    void rejectsOutOfRangeTimeLimit() {
        assertNotNull(validator.validate(withLimits(new ResourceLimit(50L, 262144L, 1024L))));
        assertNotNull(validator.validate(withLimits(new ResourceLimit(120000L, 262144L, 1024L))));
        assertNotNull(validator.validate(withLimits(new ResourceLimit(null, 262144L, 1024L))));
        assertNull(validator.validate(withLimits(new ResourceLimit(1000L, 262144L, 1024L))));
    }

    @Test
    @DisplayName("内存限制越界被拒绝")
    void rejectsOutOfRangeMemoryLimit() {
        assertNotNull(validator.validate(withLimits(new ResourceLimit(1000L, 1024L, 1024L))));
        assertNotNull(validator.validate(withLimits(new ResourceLimit(1000L, 2L * 1024 * 1024, 1024L))));
        assertNull(validator.validate(withLimits(new ResourceLimit(1000L, 262144L, 1024L))));
    }

    @Test
    @DisplayName("输出限制越界被拒绝；未提供时按默认处理")
    void rejectsOutOfRangeOutputLimit() {
        assertNotNull(validator.validate(withLimits(new ResourceLimit(1000L, 262144L, 0L))));
        assertNotNull(validator.validate(withLimits(new ResourceLimit(1000L, 262144L, 10L * 1024 * 1024))));
        assertNull(validator.validate(withLimits(new ResourceLimit(1000L, 262144L, null))),
                "未提供输出限制时应使用沙箱自身的默认上限");
    }

    private ExecutionPlan withLimits(ResourceLimit limits) {
        ExecutionPlan p = validPlan();
        p.setLimits(limits);
        return p;
    }

    // ---------- V1 入口 ----------

    @Test
    @DisplayName("V1：空代码、空用例、超量用例与超大输入被拒绝")
    void validatesV1Entry() {
        assertNotNull(validator.validateV1(null, Arrays.asList("1")));
        assertNotNull(validator.validateV1("   ", Arrays.asList("1")));
        assertNotNull(validator.validateV1("class Main{}", null));
        assertNotNull(validator.validateV1("class Main{}", Collections.emptyList()));

        List<String> many = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            many.add("1");
        }
        assertNotNull(validator.validateV1("class Main{}", many));

        assertNotNull(validator.validateV1("class Main{}",
                Collections.singletonList("x".repeat(5 * 1024 * 1024))));

        assertNull(validator.validateV1("class Main{}", Arrays.asList("1 2")));
    }

    @Test
    @DisplayName("V1/V2 两个入口的用例数上界一致，避免只堵一个入口")
    void bothEntriesShareSameCaseLimit() {
        ExecutionPlan p = validPlan();
        List<ExecutionCaseInput> cases = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            cases.add(cs("1"));
        }
        p.setCases(cases);
        assertNotNull(validator.validate(p), "V2 入口应拒绝超量用例");

        List<String> v1Cases = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            v1Cases.add("1");
        }
        assertNotNull(validator.validateV1("class Main{}", v1Cases), "V1 入口应拒绝同样的超量用例");
    }
}
