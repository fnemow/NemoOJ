package com.nemo.OJbackendmodel.model.dto.question;

import lombok.Data;

@Data
public class JudgeConfig {
    /**
     * 时间限制 (ms)
     */
    private Long timeLimit;

    /**
     * 内存限制 (KB)
     */
    private Long memoryLimit;

    /**
     * 输出大小限制 (bytes)，默认 1MB
     */
    private Long outputLimitBytes;
}
