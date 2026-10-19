package com.nemo.nemosandbox.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResourceLimit {
    private Long timeLimitMs;
    private Long memoryLimitKB;
    private Long outputLimitBytes;
}
