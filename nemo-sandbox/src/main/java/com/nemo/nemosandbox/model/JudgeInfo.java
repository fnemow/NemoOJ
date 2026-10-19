package com.nemo.nemosandbox.model;

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

    /**
     * 消耗内存 (kb)
     */
    private Long executedMemory;

    /**
     * 消耗时间 (ms)
     */
    private Long executedTime;

}