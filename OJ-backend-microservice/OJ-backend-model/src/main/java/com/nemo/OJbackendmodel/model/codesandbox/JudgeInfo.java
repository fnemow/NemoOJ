package com.nemo.OJbackendmodel.model.codesandbox;

import lombok.Data;

/**
 * 判题信息 (JSON 对象，存储在 QuestionSubmit 的 judgeInfo 字段中)
 */
@Data
public class JudgeInfo {
    /**
     * 程序执行信息 (例如：Accepted, Wrong Answer, Compile Error 等)
     */
    private String message;
    private com.nemo.OJbackendmodel.model.execution.ResourceLimit effectiveLimits;
    private String policyVersion;
    private String runtimeId;
    private String harnessVersion;

    /** 用户可见的编译或运行诊断，随提交结果持久化。 */
    private String diagnosticPhase;
    private String diagnosticMessage;
    private Boolean diagnosticTruncated;

    /**
     * 消耗内存 (kb)
     */
    private Long executedMemory;

    /**
     * 消耗时间 (ms)
     */
    private Long executedTime;
}
