package com.nemo.OJbackendmodel.model.codesandbox;

import com.nemo.OJbackendmodel.model.execution.CaseExecutionResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * V2 沙箱执行响应 —— 按用例返回结构化结果。
 * 与 V1 ExecuteCodeResponse 不同：返回每个用例的独立 CaseExecutionResult，而非扁平的 outputList。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecuteCodeResponseV2 {

    /** 每个用例的执行结果 */
    private List<CaseExecutionResult> cases;

    /** 编译消息（非 null 表示编译失败） */
    private String compileMessage;

    /** 系统级错误消息（非 null 表示系统错误，应触发重试） */
    private String systemMessage;

    /** 整体状态码（0=全部通过，其他=首个错误用例的状态码） */
    private com.nemo.OJbackendmodel.model.execution.ResourceLimit effectiveLimits;
    private String policyVersion;
    private Integer overallStatus;
}
