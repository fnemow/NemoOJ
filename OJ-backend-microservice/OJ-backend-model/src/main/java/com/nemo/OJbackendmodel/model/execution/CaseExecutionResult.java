package com.nemo.OJbackendmodel.model.execution;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单个用例的执行结果（沙箱返回的结构化数据，不包含期望答案）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CaseExecutionResult {

    /** 用例序号 */
    private Integer caseIndex;

    /** 沙箱执行状态码（参考 SandboxExecutionStatus） */
    private Integer status;

    /** 标准输出 */
    private String stdout;

    /** 标准错误输出 */
    private String stderr;

    /** 进程退出码 */
    private Integer exitCode;

    /** 执行耗时 (ms) */
    private Long time;

    /** 执行内存峰值 (KB) */
    private Long memory;
}
