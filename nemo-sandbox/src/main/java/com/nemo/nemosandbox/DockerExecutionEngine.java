package com.nemo.nemosandbox;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.model.*;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import com.github.dockerjava.core.command.PullImageResultCallback;
import com.nemo.nemosandbox.model.CaseExecutionResult;
import com.nemo.nemosandbox.runtime.LanguageRuntimeAdapter;
import com.nemo.nemosandbox.runtime.LanguageRuntimeRegistry;
import com.nemo.nemosandbox.runtime.UnsupportedLanguageException;
import com.nemo.nemosandbox.config.SandboxExecutionMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 公共 Docker 执行引擎 —— 语言无关的容器生命周期管理。
 * 从 JavaDockerCodeSandBox 提取而来，由 LanguageRuntimeAdapter 提供编译/运行命令。
 *
 * 安全配置（所有语言共用）：
 * - nobody 用户 + cap-drop=ALL + no-new-privileges
 * - 只读根文件系统，网络禁用
 * - pid 限制由 LanguagePolicy.processLimit 控制
 * - 内存+swap 等额限制
 * - 运行容器 /app ro 挂载 + tmpfs /tmp (64MB)
 */
@Slf4j
@Component
public class DockerExecutionEngine {

    @Resource
    private LanguageRuntimeRegistry runtimeRegistry;

    @Resource
    private DockerClientFactory dockerClientFactory;

    @Resource
    private SandboxExecutionMetrics executionMetrics;

    @Value("${sandbox.image-validation.enabled:true}")
    private boolean imageValidationEnabled;

    @Value("${sandbox.auto-pull:false}")
    private boolean autoPull;

    @Value("${sandbox.language.enabled:java}")
    private String enabledLanguagesStr;

    /**
     * 每个容器可用的 CPU 核数，换算为 CFS 配额（quota = 核数 × 周期）。
     * 用配置而非常量，便于按机器规格调整；改动后应由压测确认实际限速生效。
     */
    @Value("${sandbox.resources.cpu-count:1}")
    private double cpuCount;

    /** Empty disables the optional read-only host cgroup fast path. */
    @Value("${sandbox.docker.host-cgroup-root:}")
    private String hostCgroupRoot;
    @Value("${sandbox.prewarm.enabled:false}") private boolean prewarmEnabled;
    @Resource private com.nemo.nemosandbox.config.TaskConcurrencyLimiter admission;
    private final OneShotContainerPrefetch prefetch=new OneShotContainerPrefetch();

    public void finishPrewarm(String workspace){prefetch.finish(workspace);}
    @jakarta.annotation.PreDestroy public void closePrefetch(){prefetch.close();}

    private String acquireCaseContainer(DockerClient client,String image,String workspace,long memory,int pids,boolean background,boolean prepareNext) {
        // Speculate only within this exact request workspace/limits. Compile containers
        // and formal submissions never use the prefetch lane. A miss always cold-starts.
        if(!background||!prewarmEnabled)return createContainer(client,image,workspace,memory,true,pids);
        String key=workspace+"\n"+image+"\n"+memory+"\n"+pids+"\n"+cpuQuotaMicros();
        String id=prefetch.take(key);
        boolean hit=id!=null;
        if(!hit)id=createContainer(client,image,workspace,memory,true,pids);
        if(executionMetrics!=null)executionMetrics.prefetch(hit?"hit":"miss");
        if(prepareNext&&admission!=null&&admission.canPrefetch())prefetch.offer(workspace,key,()->{
            DockerClient preparation=dockerClientFactory.borrowClient();
            try {
                if(!admission.canPrefetch())return null;
                return createContainer(preparation,image,workspace,memory,true,pids);
            }catch(RuntimeException failure){
                if(executionMetrics!=null)executionMetrics.prefetch("failure");
                log.warn("One-shot prefetch failed; using cold start, error={}",failure.getClass().getSimpleName());
                throw failure;
            }finally{closeClient(preparation);}
        },unused->{DockerClient cleanup=dockerClientFactory.borrowClient();try{cleanupContainer(cleanup,unused,"unused-prefetch");}finally{closeClient(cleanup);}});
        return id;
    }

    /** CFS 配额（微秒） */
    private long cpuQuotaMicros() {
        return (long) (CPU_PERIOD_MICROS * cpuCount);
    }

    private static final long PULL_IMAGE_TIMEOUT_SECONDS = 120L;
    /** CFS 周期（微秒），Docker 默认 100ms */
    private static final long CPU_PERIOD_MICROS = 100_000L;
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

    /** 容器标签：标记为本系统创建，供清理时识别 */
    public static final String CONTAINER_LABEL_MARKER = "nemo-oj.sandbox";

    /** 容器创建时间（毫秒），供按存活时长清理 */
    public static final String CONTAINER_LABEL_CREATED_AT = "nemo-oj.created-at";

    // ============ 启动校验 ============

    @PostConstruct
    void validateImages() {
        if (!imageValidationEnabled) {
            log.info("镜像校验已禁用 (sandbox.image-validation.enabled=false)，跳过启动时 Docker 检查");
            return;
        }
        DockerClient client = dockerClientFactory.borrowClient();
        try {
            for (String lang : enabledLanguagesStr.split(",")) {
                String langTrimmed = lang.trim();
                if (langTrimmed.isEmpty()) continue;
                LanguageRuntimeAdapter adapter;
                try {
                    adapter = runtimeRegistry.get(langTrimmed);
                } catch (UnsupportedLanguageException e) {
                    throw new IllegalStateException(
                            "启用的语言 '" + langTrimmed + "' 无对应 Runtime Adapter，"
                                    + "请检查 sandbox.language.enabled 配置", e);
                }
                String imageRef = adapter.descriptor().getImageRef();
                try {
                    if (autoPull) {
                        log.info("自动拉取镜像: {}", imageRef);
                        client.pullImageCmd(imageRef)
                                .exec(new PullImageResultCallback())
                                .awaitCompletion(PULL_IMAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        log.info("镜像已拉取: {}", imageRef);
                    } else {
                        client.inspectImageCmd(imageRef).exec();
                        log.info("镜像已就绪: {}", imageRef);
                    }
                } catch (Exception e) {
                    if (autoPull) {
                        throw new IllegalStateException("镜像拉取失败: " + imageRef, e);
                    } else {
                        throw new IllegalStateException(
                                "镜像未就绪: " + imageRef
                                        + "。请预先拉取或设置 sandbox.auto-pull=true", e);
                    }
                }
            }
        } finally {
            closeClient(client);
        }
    }

    // ============ 编译 ============

    /**
     * 在 Docker 容器中编译源代码。
     *
     * @param imageRef     语言镜像
     * @param codePath     宿主机代码目录（挂载到 /app）
     * @param command      编译命令数组
     * @param env          环境变量（可为空）
     * @param timeoutSec   超时秒数
     * @param memoryBytes  内存限制
     * @param processLimit PID 限制
     * @return 编译结果
     */
    public ExecutionResult compile(String imageRef, String codePath,
                                   String[] command, Map<String, String> env,
                                   long timeoutSec, long memoryBytes, int processLimit) {
        return compile(imageRef,codePath,command,env,timeoutSec,memoryBytes,processLimit,false);
    }
    public ExecutionResult compile(String imageRef, String codePath,
                                   String[] command, Map<String, String> env,
                                   long timeoutSec, long memoryBytes, int processLimit,boolean background) {
        long stageStarted = System.nanoTime();
        DockerClient client = dockerClientFactory.borrowClient();
        String containerId = null;

        try {
            containerId = createContainer(client, imageRef, codePath, memoryBytes,
                    false, processLimit,background?128L:1024L);

            long oomBefore = readOomKills(client, containerId);
            ExecCreateCmd execCreate = client.execCreateCmd(containerId)
                    .withCmd(command).withAttachStderr(true).withAttachStdout(true);
            if (env != null && !env.isEmpty()) {
                List<String> envList = new ArrayList<>();
                for (Map.Entry<String, String> e : env.entrySet()) {
                    envList.add(e.getKey() + "=" + e.getValue());
                }
                execCreate.withEnv(envList);
            }
            ExecCreateCmdResponse execResp = execCreate.exec();

            StringBuilder stdout = new StringBuilder();
            StringBuilder stderr = new StringBuilder();
            var finished = new java.util.concurrent.CountDownLatch(1);
            ExecStartResultCallback cb = outputCallback(stdout, stderr, finished);

            var running=client.execStartCmd(execResp.getId()).exec(cb);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(timeoutSec);
            boolean completed=false;
            while(System.nanoTime()<deadline) {
                if(!workspaceWithinBudget(codePath)) return ExecutionResult.error("Compilation workspace limit exceeded",1);
                // docker-java awaitCompletion closes the stream even on timeout.
                // Poll our completion signal so long compilations retain their stream.
                if(finished.await(200,TimeUnit.MILLISECONDS)) { completed=true; break; }
            }
            boolean timedOut=!completed;
            if (timedOut) {
                return ExecutionResult.error("编译超时 (" + timeoutSec + "s)", -1);
            }
            running.awaitCompletion(); // propagate transport errors after completion

            if (isOomKilled(client, containerId) || readOomKills(client, containerId) > oomBefore) return ExecutionResult.error("Compilation memory limit exceeded",1);
            if (!workspaceWithinBudget(codePath)) return ExecutionResult.error("Compilation workspace limit exceeded",1);
            int exitCode = inspectExitCode(client, execResp.getId(), stderr.length());
            return new ExecutionResult(exitCode, stdout.toString(), stderr.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ExecutionResult.error("编译被中断", -1);
        } finally {
            if (executionMetrics != null) executionMetrics.record("compile", System.nanoTime() - stageStarted);
            if (executionMetrics != null && background) executionMetrics.record("background_compile", System.nanoTime() - stageStarted);
            cleanupContainer(client, containerId, "compile");
            closeClient(client);
        }
    }

    // ============ 单用例执行 ============

    /**
     * 在独立容器中执行单个测试用例（通过 stdin 输入）。
     *
     * @param imageRef        语言镜像
     * @param codePath        宿主机代码目录（挂载到 /app）
     * @param command         运行命令数组
     * @param env             环境变量（可为空）
     * @param stdin           标准输入
     * @param timeoutMs       超时毫秒
     * @param memoryBytes     内存限制
     * @param outputLimitBytes 输出大小限制
     * @param processLimit     PID 限制
     * @return 用例执行结果
     */
    public CaseExecutionResult executeOneCase(String imageRef, String codePath,
                                              String[] command, Map<String, String> env,
                                              String stdin,
                                              long timeoutMs, long memoryBytes,
                                              int outputLimitBytes, int processLimit) {
        return executeOneCase(imageRef,codePath,command,env,stdin,timeoutMs,memoryBytes,outputLimitBytes,processLimit,false);
    }

    public CaseExecutionResult executeOneCase(String imageRef, String codePath,
                                              String[] command, Map<String,String> env,String stdin,
                                              long timeoutMs,long memoryBytes,int outputLimitBytes,int processLimit,boolean background) {
        return executeOneCase(imageRef,codePath,command,env,stdin,timeoutMs,memoryBytes,outputLimitBytes,processLimit,background,false);
    }
    public CaseExecutionResult executeOneCase(String imageRef, String codePath,
                                              String[] command, Map<String,String> env,String stdin,
                                              long timeoutMs,long memoryBytes,int outputLimitBytes,int processLimit,boolean background,boolean prepareNext) {
        long stageStarted = System.nanoTime();
        DockerClient client = dockerClientFactory.borrowClient();
        String containerId = null;
        java.nio.file.Path inputFile = null;
        ResultCallback<Statistics> statsCb = null;

        try {
            // A regular file supplies deterministic EOF; Docker exec's attached stdin
            // does not reliably half-close when docker-java exhausts its input stream.
            inputFile = java.nio.file.Files.createTempFile(java.nio.file.Path.of(codePath), ".stdin-", ".txt");
            java.nio.file.Files.writeString(inputFile, stdin == null ? "" : stdin, StandardCharsets.UTF_8);
            java.nio.file.Files.setPosixFilePermissions(inputFile,
                    java.nio.file.attribute.PosixFilePermissions.fromString("r--r--r--"));
            String[] inputCommand = new String[command.length + 5];
            inputCommand[0] = "/bin/sh";
            inputCommand[1] = "-c";
            inputCommand[2] = "input=$1; shift; exec \"$@\" < \"$input\"";
            inputCommand[3] = "nemo-stdin";
            inputCommand[4] = "/app/" + inputFile.getFileName();
            System.arraycopy(command, 0, inputCommand, 5, command.length);
            containerId = acquireCaseContainer(client, imageRef, codePath, memoryBytes, processLimit, background, prepareNext);

            long oomBefore = readOomKills(client, containerId);
            ExecCreateCmd execCreate = client.execCreateCmd(containerId)
                    .withCmd(inputCommand)
                    .withAttachStdout(true)
                    .withAttachStderr(true);
            if (env != null && !env.isEmpty()) {
                List<String> envList = new ArrayList<>();
                for (Map.Entry<String, String> e : env.entrySet()) {
                    envList.add(e.getKey() + "=" + e.getValue());
                }
                execCreate.withEnv(envList);
            }
            ExecCreateCmdResponse execResp = execCreate.exec();

            java.io.ByteArrayOutputStream outBytes = new java.io.ByteArrayOutputStream();
            java.io.ByteArrayOutputStream errBytes = new java.io.ByteArrayOutputStream();
            boolean[] truncated = {false};
            long[] maxMem = {0L};
            final String activeContainerId = containerId;
            ExecStartResultCallback cb = new ExecStartResultCallback() {
                @Override public synchronized void onNext(Frame frame) {
                    byte[] payload = frame.getPayload();
                    if(payload != null) {
                        boolean error = StreamType.STDERR.equals(frame.getStreamType());
                        var target = error ? errBytes : outBytes;
                        int limit = error ? Math.max(4096, outputLimitBytes / 4) : outputLimitBytes;
                        int take = Math.min(payload.length, Math.max(0, limit-target.size()));
                        target.write(payload,0,take);
                        if(!error && take<payload.length && !truncated[0]) {
                            truncated[0]=true;
                            // Capture OLE evidence before killing PID 1 removes its cgroup.
                            HostCgroupMemoryReader.read(hostCgroupRoot,activeContainerId).ifPresent(snapshot -> {
                                synchronized(maxMem){maxMem[0]=Math.max(maxMem[0],snapshot.peak());}
                            });
                            try { client.killContainerCmd(activeContainerId).exec(); } catch(Exception ignored) {}
                        }
                    }
                    super.onNext(frame);
                }
            };
            StatsCmd statsCmd = background?null:client.statsCmd(containerId);
            if(statsCmd!=null)statsCb = statsCmd.exec(memCallback(maxMem));

            long start = System.currentTimeMillis();
            boolean timedOut;
            try {
                timedOut = !client.execStartCmd(execResp.getId())
                        .exec(cb).awaitCompletion(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (RuntimeException streamClosedAfterOutputLimit) {
                if (!truncated[0]) throw streamClosedAfterOutputLimit;
                timedOut = false;
            }
            long elapsed = System.currentTimeMillis() - start;
            if(statsCb!=null)try { statsCb.close(); } catch (Exception ignored) {}
            if(statsCmd!=null)try { statsCmd.close(); } catch (Exception ignored) {}
            // The stream can miss short-lived processes. cgroup v2 preserves peak
            // consumption after the submitted process exits, before container deletion.

            StringBuilder stdout = new StringBuilder(outBytes.toString(StandardCharsets.UTF_8));
            StringBuilder stderr = new StringBuilder(errBytes.toString(StandardCharsets.UTF_8));

            boolean containerOomKilled = isOomKilled(client, containerId);
            boolean oomKilled=containerOomKilled;
            if(!truncated[0] && !containerOomKilled){
                try{
                    var snapshot=readMemorySnapshot(client,containerId);
                    maxMem[0]=Math.max(maxMem[0],snapshot.peak());
                    oomKilled=snapshot.oomKills()>oomBefore;
                }
                catch(RuntimeException unavailable){if(!timedOut && !oomKilled)throw unavailable;}
            }
            int exitCode = timedOut || truncated[0] ? 124 : oomKilled ? 137 : inspectExitCode(client, execResp.getId(), stderr.length());

            // 判断状态
            int status;
            String errorMessage = null;
            if (truncated[0]) {
                stdout.append(OUTPUT_TRUNCATED_MARKER);
                status = STATUS_OLE;
                errorMessage = "Output Limit Exceeded";
            } else if (timedOut) {
                status = STATUS_TLE;
                errorMessage = "Time Limit Exceeded";
            } else if (oomKilled) {
                status = STATUS_MLE;
                errorMessage = "Memory Limit Exceeded";
            } else if (exitCode != 0) {
                status = STATUS_RUNTIME_ERROR;
                errorMessage = stderr.length() > 0
                        ? stderr.toString()
                        : "Process exited with code " + exitCode;
            } else {
                status = STATUS_SUCCESS;
            }

            return CaseExecutionResult.builder()
                    .caseIndex(null)  // 由调用方设置
                    .status(status)
                    .stdout(stdout.toString())
                    .stderr(errorMessage)
                    .exitCode(exitCode)
                    .time(elapsed)
                    .memory(maxMem[0] > 0 ? maxMem[0] / 1024 : 0L)
                    .build();

        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot prepare case input", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CaseExecutionResult.builder()
                    .status(STATUS_SYSTEM_ERROR)
                    .stderr("执行被中断")
                    .build();
        } finally {
            if(statsCb!=null)try { statsCb.close(); } catch(Exception ignored) {}
            if (executionMetrics != null) executionMetrics.record("run", System.nanoTime() - stageStarted);
            cleanupContainer(client, containerId, "run");
            closeClient(client);
            if (inputFile != null) {
                try { java.nio.file.Files.deleteIfExists(inputFile); }
                catch (java.io.IOException e) { log.warn("Cannot remove case input file", e); }
            }
        }
    }

    // ============ 容器管理 ============

    /**
     * 创建并启动容器（sleep 3600 保持运行，通过 exec 执行实际命令）。
     *
     * @param readOnlyMount true=运行容器(ro /app + tmpfs /tmp), false=编译容器(rw /app)
     * @param processLimit  PID 限制（来自 LanguagePolicy）
     */
    String createContainer(DockerClient client, String imageRef, String codePath,
                           long memoryBytes, boolean readOnlyMount, int processLimit) {
        return createContainer(client,imageRef,codePath,memoryBytes,readOnlyMount,processLimit,1024L);
    }
    String createContainer(DockerClient client, String imageRef, String codePath,
                           long memoryBytes, boolean readOnlyMount, int processLimit,long cpuShares) {
        long started = System.nanoTime();
        try {
            return createAndStartContainer(client, imageRef, codePath, memoryBytes, readOnlyMount, processLimit,cpuShares);
        } finally {
            if (executionMetrics != null) executionMetrics.record("container_create_start", System.nanoTime() - started);
        }
    }

    private String createAndStartContainer(DockerClient client, String imageRef, String codePath,
                           long memoryBytes, boolean readOnlyMount, int processLimit,long cpuShares) {
        HostConfig hostConfig = new HostConfig();
        hostConfig.withCgroupnsMode("private");
        hostConfig.withMemory(memoryBytes);
        hostConfig.withMemorySwap(memoryBytes);  // 禁止 swap
        // CPU 配额：必须用 CpuPeriod/CpuQuota。
        // docker-java 的 withCpuCount 对应 --cpus 的旧字段 CpuCount，
        // **只在 Windows 上生效**，在 Linux 上会被 Docker 忽略——
        // 也就是说目标平台上"限核"实际上没有生效。这里显式设置 CFS 配额：
        // quota/period = 1 表示 1 个 CPU，运行容器与编译容器都受限。
        hostConfig.withCpuPeriod(CPU_PERIOD_MICROS);
        hostConfig.withCpuQuota(cpuQuotaMicros());
        hostConfig.withCpuShares(Math.toIntExact(cpuShares));
        hostConfig.withCapDrop(Capability.ALL); // 移除所有 capabilities
        hostConfig.withSecurityOpts(Arrays.asList("no-new-privileges=true"));
        hostConfig.setBinds(new Bind(codePath, new Volume("/app"),
                readOnlyMount ? AccessMode.ro : AccessMode.rw));
        hostConfig.withPidsLimit((long) processLimit);
        hostConfig.withUlimits(List.of(new Ulimit("fsize", 67108864L, 67108864L)));

        // 运行容器：添加 tmpfs 供 /tmp，限制大小防止填满宿主机磁盘
        {
            Map<String, String> tmpfs = new HashMap<>();
            tmpfs.put("/tmp", "rw,noexec,nosuid,size=65536k"); // 64MB
            hostConfig.withTmpFs(tmpfs);
        }

        // 标签：清理时据此识别"本系统创建的容器"，避免误删宿主机上的其它容器。
        Map<String, String> labels = new HashMap<>();
        labels.put(CONTAINER_LABEL_MARKER, "true");
        labels.put(CONTAINER_LABEL_CREATED_AT, String.valueOf(System.currentTimeMillis()));
        labels.put("nemo-oj.phase",readOnlyMount?"execute":"compile");
        labels.put("nemo-oj.background-compile",String.valueOf(cpuShares<1024));

        long createStarted = System.nanoTime();
        CreateContainerResponse resp;
        try {
        resp = client.createContainerCmd(imageRef)
                .withLabels(labels)
                .withEntrypoint(new String[0])
                .withWorkingDir("/app")
                .withEnv("TMPDIR=/tmp")
                .withCmd("/bin/sleep", "3600")
                .withUser("65534:65534")          // 非 root
                .withHostConfig(hostConfig)
                .withNetworkDisabled(true)
                .withReadonlyRootfs(true)
                .withAttachStderr(true)
                .withAttachStdout(true)
                .withTty(false)
                .exec();
        } finally {
            if (executionMetrics != null) executionMetrics.record("container_create", System.nanoTime() - createStarted);
        }
        long startStarted = System.nanoTime();
        try {
            client.startContainerCmd(resp.getId()).exec();
        } catch (RuntimeException failedStart) {
            // The caller has not received the ID yet, so it cannot reclaim this container.
            cleanupContainer(client, resp.getId(), "start");
            throw failedStart;
        } finally {
            if (executionMetrics != null) executionMetrics.record("container_start", System.nanoTime() - startStarted);
        }
        return resp.getId();
    }

    void cleanupContainer(DockerClient client, String containerId) {
        cleanupContainer(client, containerId, "unknown");
    }

    void cleanupContainer(DockerClient client, String containerId, String stage) {
        if (containerId == null) return;
        long started = System.nanoTime();
        try {
            client.removeContainerCmd(containerId).withForce(true).exec();
        } catch (RuntimeException e) {
            if (executionMetrics != null) executionMetrics.cleanupFailed();
            log.error("沙箱容器清理失败, containerId={}, stage={}", containerId, stage, e);
        } finally {
            if (executionMetrics != null) executionMetrics.record("cleanup", System.nanoTime() - started);
        }
    }

    void closeClient(DockerClient client) {
        if (client == null) return;
        try {
            client.close();
        } catch (IOException e) {
            log.debug("关闭 DockerClient 异常", e);
        }
    }

    // ============ 退出码 / OOM 检测 ============

    int inspectExitCode(DockerClient client, String execId, int stderrLen) {
        try {
            InspectExecResponse inspect = client.inspectExecCmd(execId).exec();
            Integer code = inspect.getExitCode();
            if (code == null) throw new IllegalStateException("Missing exec exit code");
            return code;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot inspect exec exit code", e);
        }
    }

    boolean workspaceWithinBudget(String directory) {
        try(var paths=java.nio.file.Files.walk(java.nio.file.Path.of(directory))) {
            long total=0; int files=0;
            for(var path:(Iterable<java.nio.file.Path>)paths::iterator) {
                java.nio.file.attribute.BasicFileAttributes attrs;
                try { attrs=java.nio.file.Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class,java.nio.file.LinkOption.NOFOLLOW_LINKS); }
                catch (java.nio.file.NoSuchFileException removedTemporaryFile) { continue; }
                if(attrs.isSymbolicLink()) return false;
                if(attrs.isRegularFile()) {
                    total+=attrs.size();
                    if(++files>1024 || total>134217728L) return false;
                }
            }
            return true;
        } catch(java.io.IOException e) { throw new IllegalStateException("Cannot inspect compilation workspace",e); }
    }
    /** Read the persistent cgroup counter: exec OOM does not necessarily kill PID 1. */
    long readOomKills(DockerClient client, String containerId) {
        long started=System.nanoTime();
        var hostSnapshot = HostCgroupMemoryReader.read(hostCgroupRoot, containerId);
        if (hostSnapshot.isPresent()) {
            if(executionMetrics!=null)executionMetrics.record("oom_host_baseline",System.nanoTime()-started);
            return hostSnapshot.get().oomKills();
        }
        StringBuilder output=new StringBuilder(), error=new StringBuilder();
        var exec=client.execCreateCmd(containerId).withCmd("/bin/cat", "/sys/fs/cgroup/memory.events")
            .withAttachStdout(true).withAttachStderr(true).exec();
        try {
            boolean done=client.execStartCmd(exec.getId()).exec(outputCallback(output,error)).awaitCompletion(5,TimeUnit.SECONDS);
            if(!done) throw new IllegalStateException("OOM counter read timed out");
            for(String line:output.toString().split("\\R")) {
                String[] fields=line.trim().split("\\s+");
                if(fields.length==2 && fields[0].equals("oom_kill")) return Long.parseLong(fields[1]);
            }
            throw new IllegalStateException("cgroup v2 memory.events is required for reliable OOM attribution");
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("OOM counter read interrupted",e);
        } finally {
            if(executionMetrics!=null)executionMetrics.record("oom_exec_baseline",System.nanoTime()-started);
        }
    }

    record MemorySnapshot(long oomKills,long peak){}
    static MemorySnapshot parseMemorySnapshot(String value){
        Long oom=null,peak=null;
        for(String line:value.split("\\R")){
            String[] fields=line.trim().split("\\s+");
            if(fields.length==2 && fields[0].equals("oom_kill"))oom=Long.parseLong(fields[1]);
            if(fields.length==2 && fields[0].equals("peak"))peak=Long.parseLong(fields[1]);
        }
        if(oom==null || peak==null || oom<0 || peak<0)throw new IllegalStateException("Complete cgroup v2 memory snapshot required");
        return new MemorySnapshot(oom,peak);
    }
    MemorySnapshot readMemorySnapshot(DockerClient client,String containerId) {
        long started=System.nanoTime();
        var hostSnapshot=HostCgroupMemoryReader.read(hostCgroupRoot,containerId);
        if(hostSnapshot.isPresent()) {
            if(executionMetrics!=null) {
                executionMetrics.record("memory_host_snapshot",System.nanoTime()-started);
                executionMetrics.record("memory_snapshot",System.nanoTime()-started);
            }
            return hostSnapshot.get();
        }
        StringBuilder out=new StringBuilder(),err=new StringBuilder();
        var exec=client.execCreateCmd(containerId).withCmd("/bin/sh","-c","cat /sys/fs/cgroup/memory.events; printf 'peak '; cat /sys/fs/cgroup/memory.peak").withAttachStdout(true).withAttachStderr(true).exec();
        try {
            boolean done=client.execStartCmd(exec.getId()).exec(outputCallback(out,err)).awaitCompletion(5,TimeUnit.SECONDS);
            if(!done)throw new IllegalStateException("Memory snapshot read timed out");
            return parseMemorySnapshot(out.toString());
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("Memory peak read interrupted",interrupted);}
        finally{if(executionMetrics!=null){executionMetrics.record("memory_exec_snapshot",System.nanoTime()-started);executionMetrics.record("memory_snapshot",System.nanoTime()-started);}}
    }
    boolean isOomKilled(DockerClient client, String containerId) {
        try {
            InspectContainerResponse inspect = client.inspectContainerCmd(containerId).exec();
            return inspect.getState() != null
                    && Boolean.TRUE.equals(inspect.getState().getOOMKilled());
        } catch (Exception e) {
            return false;
        }
    }

    // ============ 工具方法 ============

    ExecStartResultCallback outputCallback(StringBuilder out, StringBuilder err) {
        return outputCallback(out, err, new java.util.concurrent.CountDownLatch(1));
    }

    ExecStartResultCallback outputCallback(StringBuilder out, StringBuilder err,
                                          java.util.concurrent.CountDownLatch finished) {
        return new ExecStartResultCallback() {
            private final java.io.ByteArrayOutputStream stdout = new java.io.ByteArrayOutputStream();
            private final java.io.ByteArrayOutputStream stderr = new java.io.ByteArrayOutputStream();
            @Override public synchronized void onNext(Frame frame) {
                byte[] payload=frame.getPayload();
                if(payload!=null) {
                    var target=StreamType.STDERR.equals(frame.getStreamType()) ? stderr : stdout;
                    int take=Math.min(payload.length,Math.max(0,MAX_OUTPUT_SIZE-target.size()));
                    target.write(payload,0,take);
                }
                super.onNext(frame);
            }
            @Override public synchronized void onComplete() {
                out.append(stdout.toString(StandardCharsets.UTF_8));
                err.append(stderr.toString(StandardCharsets.UTF_8));
                super.onComplete();
                finished.countDown();
            }
            @Override public void onError(Throwable error) {
                try { super.onError(error); } finally { finished.countDown(); }
            }
        };
    }
    ResultCallback<Statistics> memCallback(long[] maxMem) {
        return new ResultCallback<Statistics>() {
            private Closeable stream;
            private boolean closed;
            @Override public synchronized void onNext(Statistics s) {
                if(closed)return;
                Long u = s.getMemoryStats().getUsage();
                if (u != null) {synchronized(maxMem){maxMem[0] = Math.max(maxMem[0], u);}}
            }
            @Override public synchronized void onStart(Closeable c) {
                if(closed){try{c.close();}catch(java.io.IOException ignored){}}else stream=c;
            }
            @Override public void onError(Throwable t) {try{close();}catch(java.io.IOException ignored){}}
            @Override public void onComplete() {try{close();}catch(java.io.IOException ignored){}}
            @Override public synchronized void close() throws java.io.IOException {
                closed=true;
                if(stream!=null){Closeable active=stream;stream=null;active.close();}
            }
        };
    }

    // ============ 内部类型 ============

    /**
     * 编译执行结果。
     */
    public static class ExecutionResult {
        private final int exitCode;
        private final String stdout;
        private final String stderr;

        public ExecutionResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        static ExecutionResult error(String message, int exitCode) {
            return new ExecutionResult(exitCode, "", message);
        }

        public int getExitCode() { return exitCode; }
        public String getStdout() { return stdout; }
        public String getStderr() { return stderr; }
    }
}
