package com.nemo.nemosandbox.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CaseExecutionResult {
    private Integer caseIndex;
    private Integer status;
    private String stdout;
    private String stderr;
    private Integer exitCode;
    private Long time;
    private Long memory;
}
