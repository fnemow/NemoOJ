package com.nemo.OJbackendmodel.model.enums;

/**
 * 沙箱执行状态码
 * <p>
 * 正数 → 用户代码相关（正常完成或用户错误），Judge Service 直接写回结果，MQ 不重试。
 * 负数 → 系统错误（沙箱不可用、网络异常等），Judge Service 应触发有限重试。
 */
public final class SandboxExecutionStatus {

    private SandboxExecutionStatus() {}

    // ===== 用户侧（正数）：正常执行或用户代码问题 =====

    /** 正常完成（具体 AC/WA 由 Judge Service 比较器判断） */
    public static final int SUCCESS = 0;

    /** 编译错误（用户代码无法编译） */
    public static final int COMPILE_ERROR = 1;

    /** 运行错误（用户代码抛异常 / 非零退出） */
    public static final int RUNTIME_ERROR = 2;

    /** 时间超限（用户代码执行超过题目限制） */
    public static final int TIME_LIMIT_EXCEEDED = 3;

    /** 内存超限（用户代码内存超过题目限制） */
    public static final int MEMORY_LIMIT_EXCEEDED = 4;

    /** 输出超限（用户代码输出超过限制） */
    public static final int OUTPUT_LIMIT_EXCEEDED = 5;

    /** 不支持的语言（平台能力问题，非用户代码问题） */
    public static final int UNSUPPORTED_LANGUAGE = 6;

    // ===== 系统侧（负数）：基础设施问题，应重试 =====

    /** 沙箱系统内部错误 */
    public static final int SYSTEM_ERROR = -1;

    /** Docker 不可用 */
    public static final int DOCKER_UNAVAILABLE = -2;

    /** 沙箱编译/执行超时（进程级，非用户代码超时） */
    public static final int SANDBOX_TIMEOUT = -3;

    /** 容器被 OOM Kill（无法区分是否用户代码所致时） */
    public static final int CONTAINER_OOM_KILLED = -4;

    /**
     * 判断是否为系统错误（应触发有限重试）
     */
    public static boolean isSystemError(int status) {
        return status < 0;
    }

    /**
     * 判断是否为用户侧结果（直接写回，不重试）
     */
    public static boolean isUserResult(int status) {
        return status >= 0;
    }
}
