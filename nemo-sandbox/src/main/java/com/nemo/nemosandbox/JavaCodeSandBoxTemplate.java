package com.nemo.nemosandbox;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.nemo.nemosandbox.model.ExecuteCodeRequest;
import com.nemo.nemosandbox.model.ExecuteCodeResponse;
import com.nemo.nemosandbox.model.ExecuteMessage;
import com.nemo.nemosandbox.model.JudgeInfo;
import com.nemo.nemosandbox.model.SourceFile;
import com.nemo.nemosandbox.util.ProcessUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
public class JavaCodeSandBoxTemplate implements CodeSandBox {

    public static final String GLOBAL_CODE_DIR_NAME = "tmpCode";

    public static final String GLOBAL_CODE_CLASS_NAME = "Main.java";

    public static final Long TIME_OUT = 10000L;

    protected static String securityManagerPath;

    protected static String securityManagerClassName;

    /**
     * 完整的流程
     */
    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        List<String> inputList = executeCodeRequest.getInputList();
        String code = executeCodeRequest.getCode();
        String language = executeCodeRequest.getLanguage();

        File userCodeFile = null;
        try {
            // 1、保存用户代码为文件
            userCodeFile = saveCodeToFile(code);

            // 2、编译代码
            ExecuteMessage compileResult = compileFile(userCodeFile);
            log.info("编译结果: exitValue={}, error={}", compileResult.getExitValue(), compileResult.getErrorMessage());

            // 编译失败 → 返回编译错误 (status=1)
            if (compileResult.getExitValue() != 0) {
                return createErrorResponse("编译错误:\n" + compileResult.getErrorMessage(), 1);
            }

            // 3、执行程序
            List<ExecuteMessage> executeMessageList = runCode(userCodeFile, inputList);

            // 4、整理输出结果
            return getOutputResponse(executeMessageList);

        } catch (Exception e) {
            log.error("沙箱执行异常", e);
            return createErrorResponse("沙箱内部错误: " + e.getMessage(), -1);
        } finally {
            // 5、文件清理（无论成功失败都执行）
            if (userCodeFile != null) {
                clearFile(userCodeFile);
            }
        }
    }

    /**
     * 创建错误响应
     */
    protected ExecuteCodeResponse createErrorResponse(String message, int status) {
        ExecuteCodeResponse response = new ExecuteCodeResponse();
        response.setOutputList(new ArrayList<>());
        response.setMessage(message);
        response.setStatus(status);
        response.setJudgeInfo(new JudgeInfo());
        return response;
    }

    /**
     * 1、将用户提交的代码保存为文件
     */
    public File saveCodeToFile(String code) {
        String userDir = System.getProperty("user.dir");
        String globalCodePathName = userDir + File.separator + GLOBAL_CODE_DIR_NAME;
        if (!FileUtil.exist(globalCodePathName)) {
            FileUtil.mkdir(globalCodePathName);
        }
        String userCodeParentPath = globalCodePathName + File.separator + UUID.randomUUID();
        String userCodePath = userCodeParentPath + File.separator + GLOBAL_CODE_CLASS_NAME;
        return FileUtil.writeString(code, userCodePath, StandardCharsets.UTF_8);
    }

    /**
     * 2、编译代码
     */
    public ExecuteMessage compileFile(File userCodeFile) {
        String compileCmd = String.format("javac -encoding utf-8 %s", userCodeFile.getAbsolutePath());
        try {
            Process compileProcess = Runtime.getRuntime().exec(compileCmd);
            ExecuteMessage executeMessage = ProcessUtils.runProcessAndGetMessage(compileProcess, "编译");
            return executeMessage;
        } catch (Exception e) {
            log.error("编译进程启动失败", e);
            ExecuteMessage error = new ExecuteMessage();
            error.setExitValue(-1);
            error.setErrorMessage("编译进程启动失败: " + e.getMessage());
            return error;
        }
    }

    /**
     * 3、执行程序文件（子类覆盖）
     */
    public List<ExecuteMessage> runCode(File userCodeFile, List<String> inputList) {
        String userCodeParentPath = userCodeFile.getParentFile().getAbsolutePath();
        List<ExecuteMessage> executeMessageList = new ArrayList<>();
        for (String inputArgs : inputList) {
            String runCmd;
            if (securityManagerPath != null && securityManagerClassName != null) {
                runCmd = String.format("java -Xmx256m -Dfile.encoding=UTF-8 -cp %s;%s -Djava.security.manager=%s Main %s",
                        userCodeParentPath, securityManagerPath, securityManagerClassName, inputArgs);
            } else {
                runCmd = String.format("java -Xmx256m -Dfile.encoding=UTF-8 -cp %s Main %s",
                        userCodeParentPath, inputArgs);
            }
            try {
                Process runProcess = Runtime.getRuntime().exec(runCmd);
                // 超时控制
                new Thread(() -> {
                    try {
                        Thread.sleep(TIME_OUT);
                        runProcess.destroy();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }).start();
                ExecuteMessage executeMessage = ProcessUtils.runProcessAndGetMessage(runProcess, "运行");
                executeMessageList.add(executeMessage);
            } catch (Exception e) {
                log.error("程序执行错误", e);
                ExecuteMessage errorMsg = new ExecuteMessage();
                errorMsg.setExitValue(-1);
                errorMsg.setErrorMessage("程序执行错误: " + e.getMessage());
                executeMessageList.add(errorMsg);
            }
        }
        return executeMessageList;
    }

    /**
     * 4、获取响应输出结果
     */
    public ExecuteCodeResponse getOutputResponse(List<ExecuteMessage> executeMessageList) {
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        List<String> outputList = new ArrayList<>();
        long maxTime = 0;
        long maxMemoryBytes = 0;

        for (ExecuteMessage executeMessage : executeMessageList) {
            String errorMessage = executeMessage.getErrorMessage();
            if (StrUtil.isNotBlank(errorMessage)) {
                executeCodeResponse.setMessage(errorMessage);
                // status=2: RUNTIME_ERROR (用户代码运行错误)
                executeCodeResponse.setStatus(2);
                break;
            }
            outputList.add(executeMessage.getMessage());
            Long executeTime = executeMessage.getTime();
            if (executeTime != null) {
                maxTime = Math.max(maxTime, executeTime);
            }
            Long executeMemory = executeMessage.getMemory();
            if (executeMemory != null) {
                maxMemoryBytes = Math.max(maxMemoryBytes, executeMemory);
            }
        }
        if (outputList.size() == executeMessageList.size()) {
            // status=0: SUCCESS (正常完成)
            executeCodeResponse.setStatus(0);
        }
        executeCodeResponse.setOutputList(outputList);
        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setExecutedTime(maxTime);
        // Docker 返回 Bytes，转换为 KB 存储（与 JudgeConfig 保持一致）
        judgeInfo.setExecutedMemory(maxMemoryBytes > 0 ? maxMemoryBytes / 1024 : 0L);
        executeCodeResponse.setJudgeInfo(judgeInfo);
        return executeCodeResponse;
    }

    /**
     * 5、清理文件（V1：传入 Main.java 文件，删除其父目录 tmpCode/{uuid}/）
     */
    public boolean clearFile(File userCodeFile) {
        if (userCodeFile != null && userCodeFile.getParentFile() != null) {
            String userCodeParentPath = userCodeFile.getParentFile().getAbsolutePath();
            boolean del = FileUtil.del(userCodeParentPath);
            log.debug("清理临时文件{}: {}", del ? "成功" : "失败", userCodeParentPath);
            return del;
        }
        return true;
    }

    /**
     * V2: 直接删除工作目录（传入的 workDir 本身就是 tmpCode/{uuid}/）
     */
    public boolean clearWorkDir(File workDir) {
        if (workDir != null && workDir.exists()) {
            boolean del = FileUtil.del(workDir.getAbsolutePath());
            log.debug("V2 清理工作目录{}: {}", del ? "成功" : "失败", workDir.getAbsolutePath());
            return del;
        }
        return true;
    }

    // ======================== V2 辅助方法 ========================

    /** 合法的源文件扩展名白名单 */
    private static final Set<String> ALLOWED_EXTENSIONS = new HashSet<>(
            Arrays.asList(".java", ".py", ".cpp", ".hpp", ".h", ".inc", ".c"));

    /** 单次提交最大源文件数 */
    private static final int MAX_SOURCE_FILES = 20;
    /** 单个源文件最大大小 */
    private static final int MAX_FILE_SIZE_BYTES = 512 * 1024; // 512KB
    /** 单次提交所有源文件总大小上限 */
    private static final int MAX_TOTAL_SIZE_BYTES = 2 * 1024 * 1024; // 2MB

    /**
     * V2: 保存多个源文件到工作目录。
     * 先完整校验所有 SourceFile，全部通过后才创建目录和写入文件。
     * 校验内容：
     * - 文件数量上限
     * - 单文件大小和总大小上限
     * - 禁止 null/空/空白文件名
     * - 禁止含路径分隔符（/ 或 \）的文件名
     * - 禁止含 ".." 的文件名
     * - 禁止绝对路径
     * - 禁止重复文件名
     * - 禁止不在白名单内的扩展名
     *
     * @return 工作目录的 File 对象
     * @throws IllegalArgumentException 文件名非法或路径穿越
     */
    public File saveSourceFilesToDir(List<SourceFile> sourceFiles) {
        // ===== 第一遍：纯校验，不创建任何目录或文件 =====
        if (sourceFiles == null || sourceFiles.isEmpty()) {
            throw new IllegalArgumentException("源文件列表不能为空");
        }
        if (sourceFiles.size() > MAX_SOURCE_FILES) {
            throw new IllegalArgumentException(
                    "源文件数量超过上限: " + sourceFiles.size() + " > " + MAX_SOURCE_FILES);
        }

        int totalSize = 0;
        Set<String> seenFileNames = new HashSet<>();
        for (SourceFile sf : sourceFiles) {
            // (0) SourceFile 自身不能为 null
            if (sf == null) {
                throw new IllegalArgumentException("源文件条目不能为 null");
            }

            String fileName = sf.getFileName();
            String content = sf.getContent();

            // (1) 不允许空或空白文件名
            if (fileName == null || fileName.trim().isEmpty()) {
                throw new IllegalArgumentException("源文件名不能为空");
            }

            // (2) 内容不能为 null（后续 Hutool 写入会 NPE）
            if (content == null) {
                throw new IllegalArgumentException("源文件内容不能为空: " + fileName);
            }

            // (3) 不允许包含路径分隔符
            if (fileName.contains("/") || fileName.contains("\\")) {
                throw new IllegalArgumentException("非法文件名（含路径分隔符）: " + fileName);
            }

            // (4) 不允许包含 ".."（父目录引用）
            if (fileName.contains("..")) {
                throw new IllegalArgumentException("非法文件名（含父目录引用）: " + fileName);
            }

            // (5) 不允许绝对路径（Windows C:\ 或 Unix /...）
            if (new File(fileName).isAbsolute()) {
                throw new IllegalArgumentException("非法文件名（绝对路径）: " + fileName);
            }

            // (6) 不允许重复文件名
            if (!seenFileNames.add(fileName)) {
                throw new IllegalArgumentException("重复的源文件名: " + fileName);
            }

            // (7) 扩展名白名单校验
            int dotIdx = fileName.lastIndexOf('.');
            if (dotIdx < 0) {
                throw new IllegalArgumentException("文件名缺少扩展名: " + fileName);
            }
            String ext = fileName.substring(dotIdx).toLowerCase();
            if (!ALLOWED_EXTENSIONS.contains(ext)) {
                throw new IllegalArgumentException("不支持的文件类型: " + ext + " (" + fileName + ")");
            }

            // (8) 大小校验
            int len = content.getBytes(StandardCharsets.UTF_8).length;
            if (len > MAX_FILE_SIZE_BYTES) {
                throw new IllegalArgumentException(
                        "源文件过大: " + fileName + " (" + len + " > " + MAX_FILE_SIZE_BYTES + " bytes)");
            }
            totalSize += len;
        }

        // 总大小校验
        if (totalSize > MAX_TOTAL_SIZE_BYTES) {
            throw new IllegalArgumentException(
                    "源文件总大小超过上限: " + totalSize + " > " + MAX_TOTAL_SIZE_BYTES + " bytes");
        }

        // ===== 校验全部通过，创建目录 =====
        String userDir = System.getProperty("user.dir");
        String globalCodePathName = userDir + File.separator + GLOBAL_CODE_DIR_NAME;
        if (!FileUtil.exist(globalCodePathName)) {
            FileUtil.mkdir(globalCodePathName);
        }
        String workDirPath = globalCodePathName + File.separator + UUID.randomUUID();
        File workDir = new File(workDirPath);
        FileUtil.mkdir(workDir);

        // 规范化 workDir 用于后续边界检查
        String canonicalWorkDir;
        try {
            canonicalWorkDir = workDir.getCanonicalPath();
        } catch (IOException e) {
            // 创建后无法解析 → 清理并抛出
            FileUtil.del(workDirPath);
            throw new IllegalArgumentException("无法解析工作目录: " + workDirPath, e);
        }

        // ===== 第二遍：写入文件（此时 workDir 已创建，校验已通过） =====
        try {
            for (SourceFile sf : sourceFiles) {
                String fileName = sf.getFileName();
                File f = new File(workDir, fileName);
                try {
                    String canonicalTarget = f.getCanonicalPath();
                    if (!canonicalTarget.startsWith(canonicalWorkDir + File.separator)
                            && !canonicalTarget.equals(canonicalWorkDir)) {
                        throw new IllegalArgumentException("路径穿越检测: " + fileName);
                    }
                } catch (IOException e) {
                    throw new IllegalArgumentException("无法解析目标文件路径: " + fileName, e);
                }
                FileUtil.writeString(sf.getContent(), f, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            // 写入失败 → 清理已创建的工作目录，不留磁盘痕迹
            FileUtil.del(workDirPath);
            throw e;
        }
        return workDir;
    }

    /** 运行库资源在沙箱 jar 内的位置，由 maven-dependency-plugin 在构建期复制 */
    private static final String RUNTIME_LIB_RESOURCE = "/sandbox-libs/gson.jar";

    /** 容器内 classpath 引用的库文件名 */
    public static final String RUNTIME_LIB_FILENAME = "gson.jar";

    /**
     * V2: 把判题运行期所需的库复制到 workspace/lib/，供容器内的 javac/java 使用。
     * <p>
     * 改为按**类路径资源**读取，而不是通过 {@code Gson.class} 的 CodeSource 反查 JAR 路径。
     * 后者在可执行 Boot JAR 中会拿到 {@code jar:file:...!/BOOT-INF/lib/gson-x.jar!/}
     * 这类嵌套 URL，转换成 File 必然失败。原实现失败时只记一条 warn 就继续，
     * 结果是 Runner 编译失败被当成"用户代码编译错误"——把系统问题误报给用户。
     * <p>
     * 现在库缺失会抛出异常，由上层转成 SYSTEM_ERROR。
     *
     * @throws IllegalStateException 运行库不可用（属于部署问题，不是用户代码问题）
     */
    public void prepareLibDir(File workDir) {
        File libDir = new File(workDir, "lib");
        FileUtil.mkdir(libDir);
        File target = new File(libDir, RUNTIME_LIB_FILENAME);

        try (InputStream in = JavaCodeSandBoxTemplate.class.getResourceAsStream(RUNTIME_LIB_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                        "沙箱运行库缺失: " + RUNTIME_LIB_RESOURCE
                                + "。请确认构建时 maven-dependency-plugin 已执行（mvn package 会复制）。");
            }
            Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("复制沙箱运行库失败: " + e.getMessage(), e);
        }

        if (target.length() <= 0) {
            throw new IllegalStateException("沙箱运行库为空文件: " + target.getAbsolutePath());
        }
        log.info("运行库已准备: {} ({} bytes)", RUNTIME_LIB_FILENAME, target.length());
    }

    /**
     * V2: 编译工作目录下的所有 Java 源文件
     * @return ExecuteMessage，exitValue=0 表示成功
     */
    public ExecuteMessage compileMultipleFiles(File workDir) {
        String compileCmd = String.format("javac -encoding utf-8 %s%s*.java",
                workDir.getAbsolutePath(), File.separator);
        try {
            Process compileProcess = Runtime.getRuntime().exec(compileCmd);
            ExecuteMessage executeMessage = ProcessUtils.runProcessAndGetMessage(compileProcess, "编译");
            return executeMessage;
        } catch (Exception e) {
            log.error("V2 编译进程启动失败", e);
            ExecuteMessage error = new ExecuteMessage();
            error.setExitValue(-1);
            error.setErrorMessage("编译进程启动失败: " + e.getMessage());
            return error;
        }
    }
}
