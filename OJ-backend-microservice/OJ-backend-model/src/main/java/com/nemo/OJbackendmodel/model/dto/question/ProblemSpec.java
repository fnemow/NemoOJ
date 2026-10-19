package com.nemo.OJbackendmodel.model.dto.question;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 核心代码模式题目规格 —— 描述函数签名、返回类型和比较方式。
 * <p>
 * 当 Question.problemSpec 为 null 时表示 ACM 模式；
 * 非 null 时为 FUNCTION 模式，Judge Service 据此生成 Runner。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProblemSpec implements Serializable {

    /** Backward-compatible constructor used by existing services and tests. */
    public ProblemSpec(String mode, String className, String methodName, List<ParameterSpec> parameters,
                       String returnType, String resultSource, ComparatorSpec comparator, Integer harnessVersion) {
        this.mode = mode;
        this.className = className;
        this.methodName = methodName;
        this.parameters = parameters;
        this.returnType = returnType;
        this.resultSource = resultSource;
        this.comparator = comparator;
        this.harnessVersion = harnessVersion;
    }

    /** 执行模式：acm / function */
    private String mode;

    /** 类名（如 "Solution"） */
    private String className;

    /** 方法名（如 "reverseList"） */
    private String methodName;

    /** 方法参数列表 */
    private List<ParameterSpec> parameters;

    /** 返回值类型（如 "list_node<int>", "int[][]", "void"） */
    private String returnType;

    /** 结果来源：return / parameter:0 / parameters:[0,1] */
    private String resultSource;

    /** 比较器配置 */
    private ComparatorSpec comparator;

    /** Runner/Harness 版本号 */
    private Integer harnessVersion;

    /**
     * Optional FUNCTION-only cases. When absent, legacy function questions keep
     * using Question.judgeCase. This lets one problem retain normal ACM stdin
     * while exposing structured JSON parameters in core-code mode.
     */
    private List<JudgeCase> judgeCases;
    /** Optional bounded record-node definitions, addressed by node<ClassName>. */
    private List<CustomStructureSpec> dataStructures;

    private static final long serialVersionUID = 1L;
}
