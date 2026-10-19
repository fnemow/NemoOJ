package com.nemo.nemosandbox.controller;

import com.nemo.nemosandbox.CodeSandBox;
import com.nemo.nemosandbox.config.TaskConcurrencyLimiter;
import com.nemo.nemosandbox.validation.SandboxRequestValidator;
import com.nemo.nemosandbox.model.ExecuteCodeRequest;
import com.nemo.nemosandbox.model.ExecuteCodeRequestV2;
import com.nemo.nemosandbox.model.ExecuteCodeResponse;
import com.nemo.nemosandbox.model.ExecuteCodeResponseV2;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Slf4j
@RestController
public class SandboxController {

    @Resource
    private CodeSandBox codeSandBox;
    @Resource
    private com.nemo.nemosandbox.runtime.RuntimeCapabilities capabilities;
    @org.springframework.web.bind.annotation.GetMapping("/v2/capabilities")
    public java.util.List<java.util.Map<String,Object>> capabilities(HttpServletRequest request, HttpServletResponse response) {
        if (!secretMatches(request.getHeader(AUTH_REQUEST_HEADER))) { response.setStatus(403); return null; }
        return capabilities.snapshot();
    }

    @Resource
    private TaskConcurrencyLimiter concurrencyLimiter;

    @Resource
    private SandboxRequestValidator requestValidator;

    /**
     * 沙箱鉴权密钥。不设代码内默认值：未配置时启动即失败，
     * 避免以固定示例密钥对公网开放执行能力。
     * 必须与判题服务 judge.sandbox.secret 使用同一个环境变量。
     */
    @Value("${sandbox.auth.secret}")
    private String authSecret;

    private static final String AUTH_REQUEST_HEADER = "auth";

    /**
     * 常量时间比较，避免通过响应时间逐字节猜测密钥。
     */
    private boolean secretMatches(String provided) {
        if (provided == null) {
            return false;
        }
        return MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                authSecret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 执行代码接口
     */
    @PostMapping("/executeCode")
    ExecuteCodeResponse executeCode(@RequestBody ExecuteCodeRequest executeCodeRequest, HttpServletRequest request,
                                    HttpServletResponse response) {
        // 认证
        String authHeader = request.getHeader(AUTH_REQUEST_HEADER);
        if (!secretMatches(authHeader)) {
            log.warn("沙箱认证失败，来源IP: {}", request.getRemoteAddr());
            response.setStatus(403);
            return null;
        }
        if (executeCodeRequest == null) {
            throw new RuntimeException("请求参数为空");
        }
        if (!"java".equals(executeCodeRequest.getLanguage())) {
            response.setStatus(400);
            return null;
        }
        // 沙箱自校验：不信任调用方
        String rejectReason = requestValidator.validateV1(
                executeCodeRequest.getCode(), executeCodeRequest.getInputList());
        if (rejectReason != null) {
            log.warn("V1 请求被沙箱拒绝: {}", rejectReason);
            response.setStatus(400);
            return null;
        }
        log.info("收到代码执行请求, 语言: {}, 输入用例数: {}",
                executeCodeRequest.getLanguage(),
                executeCodeRequest.getInputList() != null ? executeCodeRequest.getInputList().size() : 0);

        // 并发闸门：编译与运行都计入容量，超限时明确拒绝而不是无限排队
        try (TaskConcurrencyLimiter.Permit permit = concurrencyLimiter.tryAcquire()) {
            if (permit == null) {
                response.setStatus(503);
                return null;
            }
            return codeSandBox.executeCode(executeCodeRequest);
        }
    }

    /**
     * V2 执行代码接口：支持多源文件、stdin 输入、逐用例结构化响应
     */
    @PostMapping("/v2/executeCode")
    ExecuteCodeResponseV2 executeCodeV2(@RequestBody ExecuteCodeRequestV2 request,
                                         HttpServletRequest httpRequest,
                                         HttpServletResponse response) {
        String authHeader = httpRequest.getHeader(AUTH_REQUEST_HEADER);
        if (!secretMatches(authHeader)) {
            log.warn("V2 沙箱认证失败，来源IP: {}", httpRequest.getRemoteAddr());
            response.setStatus(403);
            return null;
        }
        if (request == null || request.getExecutionPlan() == null) {
            throw new RuntimeException("V2 请求参数为空");
        }
        String rejectReason = requestValidator.validate(request.getExecutionPlan());
        if (rejectReason != null) {
            log.warn("V2 请求被沙箱拒绝: {}", rejectReason);
            response.setStatus(400);
            return null;
        }
        log.info("收到 V2 代码执行请求, 语言: {}, 模式: {}, 源文件数: {}, 用例数: {}",
                request.getExecutionPlan().getLanguage(),
                request.getExecutionPlan().getMode(),
                request.getExecutionPlan().getSourceFiles() != null ? request.getExecutionPlan().getSourceFiles().size() : 0,
                request.getExecutionPlan().getCases() != null ? request.getExecutionPlan().getCases().size() : 0);

        // Zero is the existing authenticated try-run request ID.
        var priority = request.isBackgroundValidation() ? TaskConcurrencyLimiter.Priority.BACKGROUND : Long.valueOf(0L).equals(request.getSubmissionId())
                ? TaskConcurrencyLimiter.Priority.TRY_RUN : TaskConcurrencyLimiter.Priority.FORMAL;
        try (TaskConcurrencyLimiter.Permit permit = concurrencyLimiter.tryAcquire(priority)) {
            if (permit == null) {
                response.setStatus(503);
                return null;
            }
            return codeSandBox.executeCodeV2(request,permit::yieldToFormal);
        }
    }
}
