package com.nemo.OJbackendmodel.model.execution;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 资源限制
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResourceLimit {

    /** 时间限制 (ms) */
    private Long timeLimitMs;

    /** 内存限制 (KB) */
    private Long memoryLimitKB;

    /** 输出大小限制 (bytes)，默认 1MB */
    private Long outputLimitBytes;
}
