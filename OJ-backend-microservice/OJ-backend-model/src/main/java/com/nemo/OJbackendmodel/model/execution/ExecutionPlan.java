package com.nemo.OJbackendmodel.model.execution;

import com.nemo.OJbackendmodel.model.enums.ExecutionModeEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 统一执行计划：描述一次判题需要编译哪些文件、按什么模式执行哪些用例。
 * 语言无关——多语言支持由 ExecutionPlan 中的 language 字段区分。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionPlan {

    /** 编程语言 */
    private String runtimeId;
    private String language;

    /** 执行模式 */
    private ExecutionModeEnum mode;

    /** 源文件列表（包含用户代码和系统 SDK 文件） */
    private List<SourceFile> sourceFiles;

    /** 测试用例输入（不包含期望答案） */
    private List<ExecutionCaseInput> cases;

    /** 资源限制 */
    private ResourceLimit limits;

    /** 入口类名（如 Main、Runner），Docker 执行时使用 */
    private String entryPoint;

    /** Runner/Harness 版本号 */
    private String harnessVersion;
}
