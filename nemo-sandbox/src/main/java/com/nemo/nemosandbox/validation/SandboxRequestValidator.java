package com.nemo.nemosandbox.validation;

import com.nemo.nemosandbox.model.ExecutionCaseInput;
import com.nemo.nemosandbox.model.ExecutionPlan;
import com.nemo.nemosandbox.model.ResourceLimit;
import com.nemo.nemosandbox.model.SourceFile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 沙箱侧的请求自校验。
 * <p>
 * 设计前提：**不完全信任调用方**。判题服务可能被替换、被绕过，
 * 或者出现配置错误；沙箱是执行任意用户代码的地方，必须自己兜住边界。
 * 即使上层已经校验过，这里也要再校验一次——这与"内部接口凭据校验"是同一思路，
 * 底层组件不把自身安全寄托在调用方身上。
 * <p>
 * 校验失败返回 {@link Rejected}，由调用方转成明确的系统错误响应，
 * 而不是让请求进入执行流程后以晦涩的方式失败。
 */
@Slf4j
@Component
public class SandboxRequestValidator {

    @Value("${sandbox.limits.max-cases:200}")
    private int maxCases;

    @Value("${sandbox.limits.max-total-input-bytes:4194304}")   // 4MB
    private long maxTotalInputBytes;

    @Value("${sandbox.limits.max-time-limit-ms:60000}")
    private long maxTimeLimitMs;

    @Value("${sandbox.limits.min-time-limit-ms:100}")
    private long minTimeLimitMs;

    @Value("${sandbox.limits.max-memory-limit-kb:1048576}")     // 1GB
    private long maxMemoryLimitKb;

    @Value("${sandbox.limits.min-memory-limit-kb:16384}")       // 16MB
    private long minMemoryLimitKb;

    @Value("${sandbox.limits.max-output-limit-bytes:1048576}")  // 1MB
    private long maxOutputLimitBytes;

    /** 校验被拒绝的原因；null 表示通过 */
    public String validate(ExecutionPlan plan) {
        if (plan == null) {
            return "executionPlan 为空";
        }
        String language = plan.getLanguage();
        if (language == null || language.trim().isEmpty()) {
            return "language 为空";
        }

        // ---- 源文件 ----
        List<SourceFile> files = plan.getSourceFiles();
        if (files == null || files.isEmpty()) {
            return "源文件列表为空";
        }
        // 数量与单文件体积由 saveSourceFilesToDir 再做一次（那里是路径安全的落点），
        // 这里只做总量与内容层面的粗筛，避免把超大请求带进执行流程
        long totalSourceBytes = 0;
        for (SourceFile f : files) {
            if (f == null || f.getFileName() == null || f.getFileName().trim().isEmpty()) {
                return "存在空文件名";
            }
            if (f.getContent() == null) {
                return "源文件内容为空: " + f.getFileName();
            }
            totalSourceBytes += f.getContent().getBytes(StandardCharsets.UTF_8).length;
        }
        if (totalSourceBytes > maxTotalInputBytes) {
            return "源码总量超限: " + totalSourceBytes + " > " + maxTotalInputBytes;
        }

        // ---- 用例 ----
        List<ExecutionCaseInput> cases = plan.getCases();
        if (cases == null || cases.isEmpty()) {
            return "用例列表为空";
        }
        if (cases.size() > maxCases) {
            return "用例数超限: " + cases.size() + " > " + maxCases;
        }
        long totalInputBytes = 0;
        for (ExecutionCaseInput c : cases) {
            if (c == null) {
                return "存在空用例";
            }
            if (c.getStdin() != null) {
                totalInputBytes += c.getStdin().getBytes(StandardCharsets.UTF_8).length;
            }
        }
        if (totalInputBytes > maxTotalInputBytes) {
            return "输入总量超限: " + totalInputBytes + " > " + maxTotalInputBytes;
        }

        // ---- 资源参数上下界 ----
        ResourceLimit limits = plan.getLimits();
        if (limits == null) {
            return "资源限制未提供";
        }
        Long timeLimitMs = limits.getTimeLimitMs();
        if (timeLimitMs == null || timeLimitMs < minTimeLimitMs || timeLimitMs > maxTimeLimitMs) {
            return "时间限制越界: " + timeLimitMs + "（允许 " + minTimeLimitMs + "~" + maxTimeLimitMs + " ms）";
        }
        Long memoryLimitKb = limits.getMemoryLimitKB();
        if (memoryLimitKb == null || memoryLimitKb < minMemoryLimitKb || memoryLimitKb > maxMemoryLimitKb) {
            return "内存限制越界: " + memoryLimitKb + "（允许 " + minMemoryLimitKb + "~" + maxMemoryLimitKb + " KB）";
        }
        Long outputLimitBytes = limits.getOutputLimitBytes();
        if (outputLimitBytes != null
                && (outputLimitBytes <= 0 || outputLimitBytes > maxOutputLimitBytes)) {
            return "输出限制越界: " + outputLimitBytes + "（上限 " + maxOutputLimitBytes + " 字节）";
        }

        // ---- 总执行期限 ----
        // 单个用例的时间上限之和就是本次任务的最坏耗时，必须有个天花板；
        // 否则调用方给出"200 个用例 × 60 秒"就能让一个请求占用沙箱 3 小时以上。
        long worstCaseMs = (long) cases.size() * timeLimitMs;
        long maxTotalMs = (long) maxCases * maxTimeLimitMs;
        if (worstCaseMs > maxTotalMs) {
            // 上界由 maxCases × maxTimeLimitMs 决定，这里保持显式判断以便将来调整策略
            return "总执行期限超限: " + worstCaseMs + " ms";
        }

        return null;
    }

    /** V1 入口的粗校验：代码长度与用例数 */
    public String validateV1(String code, List<String> inputList) {
        if (code == null || code.trim().isEmpty()) {
            return "代码为空";
        }
        // 与 JavaCodeSandBoxTemplate 的单文件上限保持一致（512KB）
        int codeBytes = code.getBytes(StandardCharsets.UTF_8).length;
        if (codeBytes > 512 * 1024) {
            return "代码过大: " + codeBytes + " 字节";
        }
        if (inputList == null || inputList.isEmpty()) {
            return "输入用例列表为空";
        }
        if (inputList.size() > maxCases) {
            return "用例数超限: " + inputList.size() + " > " + maxCases;
        }
        long total = 0;
        for (String s : inputList) {
            if (s != null) {
                total += s.getBytes(StandardCharsets.UTF_8).length;
            }
        }
        if (total > maxTotalInputBytes) {
            return "输入总量超限: " + total + " > " + maxTotalInputBytes;
        }
        return null;
    }
}
