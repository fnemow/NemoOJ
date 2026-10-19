package com.nemo.OJbackendmodel.model.execution;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单个执行用例的输入（只包含 stdin，不包含期望答案）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionCaseInput {

    /** 用例序号（从 0 开始） */
    private Integer caseIndex;

    /** 标准输入内容（ACM 模式传入 stdin，核心代码模式传入 JSON 参数） */
    private String stdin;
}
