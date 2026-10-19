package com.nemo.nemosandbox;

import cn.hutool.core.util.ArrayUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.nemo.nemosandbox.model.*;
import com.nemo.nemosandbox.runtime.*;
import com.nemo.nemosandbox.config.SandboxExecutionMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.Closeable;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Docker 沙箱入口 —— 实现 CodeSandBox 接口，V1 和 V2 协议均委托给 DockerExecutionEngine。
 * 语言特定逻辑通过 LanguageRuntimeAdapter 获取，不再硬编码 javac/java 命令。
 */
@Slf4j
@Component("javaDockerCodeSandBox")
@ConditionalOnProperty(name = "sandbox.type", havingValue = "docker", matchIfMissing = true)
public class JavaDockerCodeSandBox extends JavaCodeSandBoxTemplate implements CodeSandBox {

    @Resource
    private DockerExecutionEngine engine;

    @Resource
    private LanguageRuntimeRegistry runtimeRegistry;

    @Resource
    private DockerClientFactory dockerClientFactory;

    @Resource
    private SandboxExecutionMetrics executionMetrics;

    @Resource
    private TrustedCompileArtifactCache compileCache;

    @org.springframework.beans.factory.annotation.Value("${sandbox.language.enabled:java}")
    private String enabledLanguages = "java";
    @org.springframework.beans.factory.annotation.Value("${sandbox.limits.max-task-time-ms:600000}")
    private long maxTaskTimeMs = 600000;
    @Resource
    private org.springframework.core.env.Environment languageEnvironment;

    // 安全默认值（题目未配置时使用）
    private static final long DEFAULT_COMPILE_TIMEOUT_SECONDS = 30L;
    private static final long DEFAULT_EXEC_TIMEOUT_MS = 10_000L;
    private static final long DEFAULT_RUN_MEMORY_BYTES = 256L * 1024 * 1024; // 256MB
    private static final long COMPILE_MEMORY_BYTES = 512L * 1024 * 1024;      // 512MB
    private static final long MAX_MEMORY_BYTES = 1024L * 1024 * 1024;         // 1GB
    private static final int MAX_OUTPUT_SIZE = 1 * 1024 * 1024; // 1MB
    private static final String OUTPUT_TRUNCATED_MARKER = "\n[OUTPUT TRUNCATED]";

    // 状态码
    private static final int STATUS_SUCCESS = 0;
    private static final int STATUS_COMPILE_ERROR = 1;
    private static final int STATUS_RUNTIME_ERROR = 2;
    private static final int STATUS_TLE = 3;
    private static final int STATUS_MLE = 4;
    private static final int STATUS_OLE = 5;
    private static final int STATUS_UNSUPPORTED_LANGUAGE = 6;
    private static final int STATUS_SYSTEM_ERROR = -1;

    // ============ V1 入口（保持向后兼容） ============

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest req) {
        List<String> inputList = req.getInputList();
        String code = req.getCode();

        long execTimeoutMs = req.getTimeLimitMs() != null
                ? Math.max(req.getTimeLimitMs(), 100L)
                : DEFAULT_EXEC_TIMEOUT_MS;
        long runMemoryBytes = req.getMemoryLimitKB() != null
                ? Math.min(req.getMemoryLimitKB() * 1024, MAX_MEMORY_BYTES)
                : DEFAULT_RUN_MEMORY_BYTES;

        log.info("V1 沙箱执行: 编译超时={}s, 用例超时={}ms, 运行内存={}MB",
                DEFAULT_COMPILE_TIMEOUT_SECONDS, execTimeoutMs, runMemoryBytes / 1024 / 1024);

        // V1 固定使用 Java 运行时
        LanguageRuntimeAdapter runtime = runtimeRegistry.get("java");
        String imageRef = runtime.descriptor().getImageRef();

        File userCodeFile = null;
        try {
            userCodeFile = saveCodeToFile(code);
            makeWorldWritable(userCodeFile.getParentFile());

            // 编译
            String codeParentPath = userCodeFile.getParentFile().getAbsolutePath();
            String[] compileCmd = {"javac", "-encoding", "utf-8",
                    "/app/" + userCodeFile.getName()};
            LanguagePolicy v1policy = runtime.policy();
            DockerExecutionEngine.ExecutionResult compileResult = engine.compile(
                    imageRef, codeParentPath, compileCmd, java.util.Collections.emptyMap(),
                    DEFAULT_COMPILE_TIMEOUT_SECONDS, COMPILE_MEMORY_BYTES,
                    v1policy.getProcessLimit());
            if (compileResult.getExitCode() != 0) {
                return buildResponse(new ArrayList<>(),
                        "编译错误:\n" + compileResult.getStderr(),
                        STATUS_COMPILE_ERROR, null);
            }

            // 逐用例执行（命令行参数模式）
            List<ExecuteMessage> results = new ArrayList<>();
            for (String inputArgs : inputList) {
                ExecuteMessage em = executeOneCaseV1(userCodeFile, inputArgs,
                        execTimeoutMs, runMemoryBytes);
                results.add(em);
                if (em.getErrorMessage() != null && !em.getErrorMessage().isEmpty()) {
                    break;
                }
            }
            return getOutputResponse(results);

        } catch (Exception e) {
            log.error("V1 Docker 沙箱异常", e);
            return buildResponse(new ArrayList<>(), e.getMessage(), STATUS_SYSTEM_ERROR, null);
        } finally {
            if (userCodeFile != null) clearFile(userCodeFile);
        }
    }

    /**
     * V1 单用例执行 —— 命令行参数模式（保留原实现，委托 engine 做容器管理）。
     */
    private ExecuteMessage executeOneCaseV1(File userCodeFile, String inputArgs,
                                            long timeoutMs, long memoryBytes) {
        LanguageRuntimeAdapter runtime = runtimeRegistry.get("java");
        String imageRef = runtime.descriptor().getImageRef();
        String codeParentPath = userCodeFile.getParentFile().getAbsolutePath();
        DockerClient client = dockerClientFactory.createClient();
        String containerId = null;

        try {
            containerId = engine.createContainer(client, imageRef, codeParentPath,
                    memoryBytes, true, runtime.policy().getProcessLimit());

            String[] cmd = ArrayUtil.append(
                    new String[]{"java", "-cp", "/app", "Main"},
                    inputArgs.split(" "));
            ExecCreateCmdResponse execResp = client.execCreateCmd(containerId)
                    .withCmd(cmd).withAttachStderr(true).withAttachStdout(true).exec();

            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            int[] bytesWritten = {0};  // 累计已写入的 stdout 字节数
            boolean[] truncated = {false};
            ExecStartResultCallback cb = new ExecStartResultCallback() {
                @Override
                public void onNext(Frame frame) {
                    byte[] payload = frame.getPayload();
                    if (payload == null) { super.onNext(frame); return; }
                    if (truncated[0]) { super.onNext(frame); return; }
                    if (StreamType.STDERR.equals(frame.getStreamType())) {
                        int stderrLimit = Math.max(4096, MAX_OUTPUT_SIZE / 4);
                        if (stderr.length() < stderrLimit) {
                            stderr.append(new String(payload, StandardCharsets.UTF_8));
                        }
                    } else {
                        int remaining = MAX_OUTPUT_SIZE - bytesWritten[0];
                        if (remaining <= 0) {
                            truncated[0] = true;
                        } else if (payload.length <= remaining) {
                            stdout.append(new String(payload, StandardCharsets.UTF_8));
                            bytesWritten[0] += payload.length;
                        } else {
                            stdout.append(new String(payload, 0, remaining, StandardCharsets.UTF_8));
                            bytesWritten[0] += remaining;
                            truncated[0] = true;
                        }
                    }
                    super.onNext(frame);
                }
            };

            long[] maxMem = {0L};
            StatsCmd statsCmd = client.statsCmd(containerId);
            ResultCallback<Statistics> statsCb = statsCmd.exec(new ResultCallback<Statistics>() {
                @Override public void onNext(Statistics s) {
                    Long u = s.getMemoryStats().getUsage();
                    if (u != null) maxMem[0] = Math.max(maxMem[0], u);
                }
                @Override public void onStart(Closeable c) {}
                @Override public void onError(Throwable t) {}
                @Override public void onComplete() {}
                @Override public void close() {}
            });

            long start = System.currentTimeMillis();
            boolean timedOut = !client.execStartCmd(execResp.getId())
                    .exec(cb).awaitCompletion(timeoutMs, TimeUnit.MILLISECONDS);
            long elapsed = System.currentTimeMillis() - start;
            try { statsCmd.close(); } catch (Exception ignored) {}

            int exitCode = timedOut ? 124 : engine.inspectExitCode(client, execResp.getId(), stderr.length());
            boolean oomKilled = engine.isOomKilled(client, containerId);

            ExecuteMessage msg = new ExecuteMessage();
            msg.setTime(elapsed);
            msg.setMemory(maxMem[0]);
            msg.setMessage(stdout.toString());
            msg.setExitValue(exitCode);

            if (truncated[0]) {
                stdout.append(OUTPUT_TRUNCATED_MARKER);
                msg.setMessage(stdout.toString());
                msg.setErrorMessage("Output Limit Exceeded");
            } else if (timedOut) {
                msg.setErrorMessage("Time Limit Exceeded");
            } else if (oomKilled) {
                msg.setErrorMessage("Memory Limit Exceeded");
            } else if (exitCode != 0) {
                msg.setErrorMessage(stderr.length() > 0
                        ? stderr.toString()
                        : "Process exited with code " + exitCode);
            } else if (stderr.length() > 0) {
                log.debug("V1 用例退出码0但stderr有输出: {}",
                        stderr.substring(0, Math.min(100, stderr.length())));
            }
            return msg;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return errorMsg("执行被中断");
        } finally {
            engine.cleanupContainer(client, containerId, "legacy-run");
            engine.closeClient(client);
        }
    }

    private ExecuteMessage errorMsg(String msg) {
        ExecuteMessage em = new ExecuteMessage();
        em.setExitValue(-1);
        em.setErrorMessage(msg);
        return em;
    }

    // ============ V2 入口（委托给 DockerExecutionEngine + Runtime Adapter） ============

    @Override
    public ExecuteCodeResponseV2 executeCodeV2(ExecuteCodeRequestV2 request) {
        return executeCodeV2(request,()->{});
    }

    @Override
    public ExecuteCodeResponseV2 executeCodeV2(ExecuteCodeRequestV2 request,Runnable betweenContainers) {
        ExecutionPlan plan = request.getExecutionPlan();
        if (plan == null) {
            return buildV2ErrorResponse(new ArrayList<>(), "执行计划为空", STATUS_SYSTEM_ERROR);
        }

        // Layer 3: Sandbox 层语言门禁（终局拒绝，不重试）
        LanguageRuntimeAdapter runtime;
        try {
            if (!Arrays.asList(enabledLanguages.split(",")).stream().map(String::trim).anyMatch(plan.getLanguage()::equals)) throw new UnsupportedLanguageException(plan.getLanguage());
            if (languageEnvironment != null) {
                var modes = org.springframework.boot.context.properties.bind.Binder.get(languageEnvironment)
                    .bind("sandbox.language.modes." + plan.getLanguage(), org.springframework.boot.context.properties.bind.Bindable.listOf(String.class))
                    .orElse(List.of("ACM", "FUNCTION"));
                if (plan.getMode() == null || !modes.contains(plan.getMode().name())) throw new UnsupportedLanguageException(plan.getLanguage());
            }
            runtime = runtimeRegistry.get(plan.getLanguage());
            if (plan.getRuntimeId() != null && !plan.getRuntimeId().equals(RuntimeCapabilities.runtimeId(runtime))) throw new UnsupportedLanguageException(plan.getLanguage());
        } catch (UnsupportedLanguageException e) {
            log.warn("不支持的语言: {}, submissionId={}", plan.getLanguage(), request.getSubmissionId());
            // 不写入 systemMessage（否则 Judge Service 会当系统错误重试）
            return ExecuteCodeResponseV2.builder()
                    .cases(new ArrayList<>())
                    .overallStatus(STATUS_UNSUPPORTED_LANGUAGE)
                    .build();
        }

        try { ExecutionLayout.validate(plan, runtime.descriptor()); } catch (IllegalArgumentException e) {
            return buildV2ErrorResponse(new ArrayList<>(), e.getMessage(), STATUS_SYSTEM_ERROR);
        }
        List<ExecutionCaseInput> cases = plan.getCases();
        List<SourceFile> sourceFiles = plan.getSourceFiles();
        if (sourceFiles == null || sourceFiles.isEmpty()) {
            return buildV2ErrorResponse(new ArrayList<>(), "源文件为空", STATUS_SYSTEM_ERROR);
        }

        LanguagePolicy policy = runtime.policy();
        long execTimeoutMs = DEFAULT_EXEC_TIMEOUT_MS;
        long runMemoryBytes = DEFAULT_RUN_MEMORY_BYTES;
        int outputLimitBytes = policy.getOutputLimitBytes();
        if (plan.getLimits() != null) {
            if (plan.getLimits().getTimeLimitMs() != null) {
                long value = plan.getLimits().getTimeLimitMs();
                if (value < 100 || value > 60000) return buildV2ErrorResponse(new ArrayList<>(), "Invalid base time budget", STATUS_SYSTEM_ERROR);
                execTimeoutMs = value;
            }
            if (plan.getLimits().getMemoryLimitKB() != null) {
                long value = plan.getLimits().getMemoryLimitKB();
                if (value < 16384 || value > MAX_MEMORY_BYTES / 1024) return buildV2ErrorResponse(new ArrayList<>(), "Invalid memory budget", STATUS_SYSTEM_ERROR);
                runMemoryBytes = value * 1024;
            }
            if (plan.getLimits().getOutputLimitBytes() != null) {
                long val = plan.getLimits().getOutputLimitBytes();
                if (val <= 0 || val > policy.getOutputLimitBytes()) return buildV2ErrorResponse(new ArrayList<>(), "Invalid output budget", STATUS_SYSTEM_ERROR);
                outputLimitBytes = (int) val;
            }
        }

        // 应用语言运行时补偿系数（Python 等语言需要更多时间）
        double effectiveTime = Math.ceil(execTimeoutMs * policy.getRuntimeFactor());
        if (!Double.isFinite(effectiveTime) || effectiveTime <= 0 || effectiveTime > 180000) {
            return buildV2ErrorResponse(new ArrayList<>(), "Effective time budget out of range", STATUS_SYSTEM_ERROR);
        }
        execTimeoutMs = (long) effectiveTime;

        long totalBudget = policy.getCompileTimeoutSeconds() * 1000 + (cases == null ? 0 : cases.size()) * (execTimeoutMs + 10000);
        if (totalBudget > maxTaskTimeMs) return buildV2ErrorResponse(new ArrayList<>(), "Total execution budget exceeds platform limit", STATUS_SYSTEM_ERROR);
        String imageRef = runtime.descriptor().getImageRef();

        log.info("V2 沙箱执行: language={}, image={}, 源文件数={}, 用例数={}, 超时={}ms, 内存={}MB",
                plan.getLanguage(), imageRef, sourceFiles.size(),
                cases != null ? cases.size() : 0,
                execTimeoutMs, runMemoryBytes / 1024 / 1024);

        File workDir = null;
        try {
            long workspaceStarted = System.nanoTime();
            // 1) 保存所有源文件到工作目录
            workDir = saveSourceFilesToDir(sourceFiles);
            if ("java".equals(plan.getLanguage())) prepareLibDir(workDir);
            prepareWorkspacePermissions(workDir);
            File libDir = new File(workDir, "lib");
            if (libDir.exists()) {
                prepareWorkspacePermissions(libDir);
            }
            executionMetrics.record("workspace", System.nanoTime() - workspaceStarted);

            // 2) 构建 WorkspaceManifest
            List<String> savedNames = new ArrayList<>();
            for (SourceFile sf : sourceFiles) {
                if (sf.getFileName() != null) {
                    savedNames.add(sf.getFileName());
                }
            }
            WorkspaceManifest ws = new WorkspaceManifest(
                    plan.getMode(),
                    prefixWithApp(savedNames),
                    plan.getEntryPoint() != null ? plan.getEntryPoint() : "Main"
            );

            // 3) 编译（解释型语言跳过）
            java.util.Optional<CommandSpec> compileCmd = runtime.compileCommand(ws);
            if (compileCmd.isPresent()) {
                CommandSpec cspec = compileCmd.get();
                String cacheKey = compileCache == null ? null : compileCache.key(request, runtime, cspec, workDir.toPath());
                boolean restored = cacheKey != null && compileCache.restore(cacheKey, plan.getLanguage(), workDir.toPath());
                if (cacheKey != null) executionMetrics.compileCache(restored ? "hit" : "miss");
                if (!restored) {
                betweenContainers.run();
                DockerExecutionEngine.ExecutionResult cr = request.isBackgroundValidation() ? engine.compile(
                        imageRef, workDir.getAbsolutePath(), cspec.getCommand(), cspec.getEnv(),
                        policy.getCompileTimeoutSeconds(),policy.getCompileMemoryBytes(),policy.getProcessLimit(),true) : engine.compile(
                        imageRef, workDir.getAbsolutePath(),
                        cspec.getCommand(), cspec.getEnv(),
                        policy.getCompileTimeoutSeconds(),
                        policy.getCompileMemoryBytes(),
                        policy.getProcessLimit());
                if (cr.getExitCode() != 0) {
                    return ExecuteCodeResponseV2.builder()
                            .cases(new ArrayList<>())
                            .compileMessage("编译错误:\n" + cr.getStderr())
                            .overallStatus(STATUS_COMPILE_ERROR)
                            .build();
                }
                if (cacheKey != null) executionMetrics.compileCache(compileCache.store(cacheKey, plan.getLanguage(), workDir.toPath()) ? "stored" : "store_skipped");
                }
            }

            // 4) 逐用例执行
            CommandSpec runCmd = runtime.runCommand(ws);
            List<CaseExecutionResult> caseResults = new ArrayList<>();
            if (cases != null) {
                String codePath = workDir.getAbsolutePath();
                int remaining=cases.size();
                for (ExecutionCaseInput caseInput : cases) {
                    betweenContainers.run();
                    CaseExecutionResult result = engine.executeOneCase(
                            imageRef, codePath,
                            runCmd.getCommand(), runCmd.getEnv(),
                            caseInput.getStdin(),
                            execTimeoutMs, runMemoryBytes,
                            outputLimitBytes,
                            policy.getProcessLimit(),request.isBackgroundValidation(),--remaining>0);
                    result.setCaseIndex(caseInput.getCaseIndex());
                    caseResults.add(result);
                    if (result.getStatus() != null && (result.getStatus() < 0
                            || (request.isBackgroundValidation() && result.getStatus() != STATUS_SUCCESS))) {
                        break;
                    }
                }
            }

            // 5) 计算整体状态
            int overallStatus = STATUS_SUCCESS;
            for (CaseExecutionResult cr : caseResults) {
                if (cr.getStatus() != null && cr.getStatus() != STATUS_SUCCESS) {
                    overallStatus = cr.getStatus();
                    break;
                }
            }

            return ExecuteCodeResponseV2.builder()
                    .effectiveLimits(ResourceLimit.builder().timeLimitMs(execTimeoutMs).memoryLimitKB(runMemoryBytes / 1024).outputLimitBytes((long) outputLimitBytes).build())
                    .policyVersion(RuntimeCapabilities.policyVersion(runtime))
                    .cases(caseResults)
                    .overallStatus(overallStatus)
                    .build();

        } catch (Exception e) {
            log.error("V2 Docker 沙箱异常", e);
            return buildV2ErrorResponse(new ArrayList<>(), e.getMessage(), STATUS_SYSTEM_ERROR);
        } finally {
            if (workDir != null) {
                engine.finishPrewarm(workDir.getAbsolutePath());
                clearWorkDir(workDir);
            }
        }
    }

    // ============ 工具方法 ============

    private List<String> prefixWithApp(List<String> fileNames) {
        List<String> result = new ArrayList<>();
        for (String name : fileNames) {
            result.add("/app/" + name);
        }
        return result;
    }

    private ExecuteCodeResponseV2 buildV2ErrorResponse(List<CaseExecutionResult> cases,
                                                        String systemMessage, int overallStatus) {
        return ExecuteCodeResponseV2.builder()
                .cases(cases)
                .systemMessage(systemMessage)
                .overallStatus(overallStatus)
                .build();
    }

    @Override
    public ExecuteCodeResponse getOutputResponse(List<ExecuteMessage> list) {
        List<String> outputs = new ArrayList<>();
        long maxTime = 0, maxMemBytes = 0;

        for (ExecuteMessage em : list) {
            String err = em.getErrorMessage();
            if (err != null && !err.isEmpty()) {
                outputs.add(em.getMessage() != null ? em.getMessage() : "");
                int status = classifyError(err);
                JudgeInfo ji = new JudgeInfo();
                ji.setExecutedTime(em.getTime() != null ? em.getTime() : 0L);
                ji.setExecutedMemory(em.getMemory() != null ? em.getMemory() / 1024 : 0L);
                return buildResponse(outputs, err, status, ji);
            }
            outputs.add(em.getMessage() != null ? em.getMessage() : "");
            if (em.getTime() != null) maxTime = Math.max(maxTime, em.getTime());
            if (em.getMemory() != null) maxMemBytes = Math.max(maxMemBytes, em.getMemory());
        }
        JudgeInfo ji = new JudgeInfo();
        ji.setExecutedTime(maxTime);
        ji.setExecutedMemory(maxMemBytes > 0 ? maxMemBytes / 1024 : 0L);
        return buildResponse(outputs, null, STATUS_SUCCESS, ji);
    }

    private int classifyError(String err) {
        if (err == null) return STATUS_SUCCESS;
        if (err.contains("Time Limit Exceeded")) return STATUS_TLE;
        if (err.contains("Memory Limit Exceeded")) return STATUS_MLE;
        if (err.contains("Output Limit Exceeded")) return STATUS_OLE;
        return STATUS_RUNTIME_ERROR;
    }

    private ExecuteCodeResponse buildResponse(List<String> outputs, String msg, int status, JudgeInfo ji) {
        ExecuteCodeResponse resp = new ExecuteCodeResponse();
        resp.setOutputList(outputs);
        resp.setMessage(msg);
        resp.setStatus(status);
        resp.setJudgeInfo(ji != null ? ji : new JudgeInfo());
        return resp;
    }

    /** Service owns the directory; only the fixed sandbox group can write it. */
    private void prepareWorkspacePermissions(File dir) throws java.io.IOException {
        if (!java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) return;
        java.nio.file.Files.setAttribute(dir.toPath(), "unix:gid", 65534);
        java.nio.file.Files.setPosixFilePermissions(dir.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwx---"));
        File parent = dir.getParentFile();
        if (parent != null && GLOBAL_CODE_DIR_NAME.equals(parent.getName())) {
            java.nio.file.Files.setPosixFilePermissions(parent.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        }
    }
    /** 将目录设为所有人可写（容器 nobody 用户需要写权限） */
    private void makeWorldWritable(File dir) {
        if (dir == null) return;
        dir.setReadable(true, false);
        dir.setWritable(true, false);
        dir.setExecutable(true, false);
    }
}
