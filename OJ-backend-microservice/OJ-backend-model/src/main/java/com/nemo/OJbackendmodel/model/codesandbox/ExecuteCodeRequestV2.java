package com.nemo.OJbackendmodel.model.codesandbox;

import com.nemo.OJbackendmodel.model.execution.ExecutionPlan;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * V2 沙箱执行请求 —— 包含完整执行计划。
 * 与 V1 ExecuteCodeRequest 不同：使用 ExecutionPlan 替代 inputList + code + language 的简单模型。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecuteCodeRequestV2 {

    /** 提交 ID（用于链路追踪） */
    private Long submissionId;
    private boolean backgroundValidation;
    /** Eligible only for validated platform templates; not supplied by browser APIs. */
    private String trustedCompileScope;

    /** 执行计划 */
    private ExecutionPlan executionPlan;
}
